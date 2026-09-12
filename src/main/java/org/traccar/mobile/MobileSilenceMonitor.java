package org.traccar.mobile;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.LifecycleObject;
import org.traccar.database.NotificationManager;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Evaluador de presencia: cada 30 s recorre los dispositivos con jornada activa
 * (más los que el tracker aún sigue con presencia viva) y aplica
 * {@link MobilePresenceTracker#evaluate}. Las transiciones ONLINE/SUSPECT/OFFLINE
 * las decide el tracker con eventos dedicados; este monitor conserva el chequeo
 * STALLED (datos sin movimiento) y la marca {@code mobile.degraded} para el panel.
 *
 * <p>Se eliminó el hair-trigger anterior (evento a los 2 min de silencio por
 * lastUpdate): eso declaraba problemas ante cualquier handover, reintento MQTT o
 * buffering offline. Ahora el silencio primero es SUSPECT (5 min) y solo después
 * OFFLINE (10 min), con motivo (TIMEOUT/SESSION_LOST/JOURNEY_ENDED) y recuperación
 * explícita. Ver {@link MobilePresenceTracker}.
 */
@Singleton
public class MobileSilenceMonitor implements LifecycleObject {

    private static final Logger LOGGER = LoggerFactory.getLogger(MobileSilenceMonitor.class);
    private static final long COOLDOWN_MS = 15 * 60_000L;
    /** Jornada activa con mensajes pero sin coordenadas nuevas >= 15 min. */
    private static final long STALLED_THRESHOLD_MS = 15 * 60_000L;
    /** Resumen de métricas mobile.stats cada ~10 min (cada 20 ticks). */
    private static final int STATS_EVERY_TICKS = 20;

    private final Storage storage;
    private final NotificationManager notificationManager;
    private final MobileJourneyRegistry journeyRegistry;
    private final MobileQualityFilter qualityFilter;
    private final MobilePresenceTracker tracker;
    private final MobileIngestionService ingestion;
    private ScheduledExecutorService scheduler;

    private final java.util.concurrent.ConcurrentHashMap<Long, Long> lastStalledByDevice =
            new java.util.concurrent.ConcurrentHashMap<>();
    private long tickCount;

    @Inject
    public MobileSilenceMonitor(Storage storage, NotificationManager notificationManager,
            MobileJourneyRegistry journeyRegistry, MobileQualityFilter qualityFilter,
            MobilePresenceTracker tracker, MobileIngestionService ingestion) {
        this.storage = storage;
        this.notificationManager = notificationManager;
        this.journeyRegistry = journeyRegistry;
        this.qualityFilter = qualityFilter;
        this.tracker = tracker;
        this.ingestion = ingestion;
    }

    @Override
    public synchronized void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MobileSilenceMonitor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::check, 30, 30, TimeUnit.SECONDS);
        LOGGER.info("Mobile presence evaluator started (period=30s)");
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void check() {
        try {
            long now = System.currentTimeMillis();
            // Unión: jornadas activas + presencias aún seguidas (cubre el caso de
            // vista ONLINE con registry inactivo, que el temporizador degrada solo).
            Set<Long> deviceIds = new HashSet<>(journeyRegistry.activeDeviceIds());
            deviceIds.addAll(tracker.trackedIds());
            for (Long deviceId : deviceIds) {
                Device device;
                try {
                    device = storage.getObject(Device.class, new Request(
                            new Columns.Include("id", "uniqueId", "status", "lastUpdate", "attributes"),
                            new Condition.Equals("id", deviceId)));
                } catch (Exception lookupError) {
                    LOGGER.warn("Silence monitor: failed to load device {}", deviceId, lookupError);
                    continue;
                }
                if (device == null) {
                    continue;
                }
                MobilePresenceTracker.Transition transition = tracker.evaluate(device, now);
                if (transition.changed() && (transition.to() == MobilePresenceTracker.PresenceState.SUSPECT
                        || transition.to() == MobilePresenceTracker.PresenceState.OFFLINE)) {
                    // Compatibilidad con el panel (SIN SEÑAL): degradado mientras no
                    // haya presencia sana. La recuperación la limpia el applier con
                    // telemetría sana en el mismo UPDATE del mensaje.
                    persistDegraded(device, true);
                }
                if (journeyRegistry.isActive(deviceId)) {
                    checkStalled(device, now);
                }
            }
            if (++tickCount % STATS_EVERY_TICKS == 0) {
                List<Long> active = new ArrayList<>(journeyRegistry.activeDeviceIds());
                LOGGER.info("mobile.stats activeJourneys={} {} {}",
                        active.size(), ingestion.formatOutcomeStats(), tracker.formatStats());
            }
        } catch (Exception error) {
            LOGGER.warn("Mobile silence monitor check failed", error);
        }
    }

    /**
     * Jornada activa que sigue enviando mensajes pero no produce coordenadas nuevas
     * (GNSS muerto, todo re-entregas de red): el panel parece vivo pero la ruta en
     * carretera no se dibuja. Un evento cada 15 min; se resetea al volver el movimiento.
     */
    private void checkStalled(Device device, long now) {
        long lastMove = qualityFilter.lastMovementMs(device.getId());
        if (now - lastMove < STALLED_THRESHOLD_MS) {
            lastStalledByDevice.remove(device.getId());
            return;
        }
        Long lastStalled = lastStalledByDevice.get(device.getId());
        if (lastStalled != null && now - lastStalled < COOLDOWN_MS) {
            return;
        }
        Event event = new Event(Event.TYPE_MOBILE_STALLED, device.getId());
        event.getAttributes().put("mobileSeverity", "warning");
        event.getAttributes().put("minutesWithoutMovement", (now - lastMove) / 60_000);
        String network = strAttr(device, "mobile.network");
        String gps = strAttr(device, "mobile.gps");
        if (network != null) {
            event.getAttributes().put("network", network);
        }
        if (gps != null) {
            event.getAttributes().put("gps", gps);
        }
        try {
            notificationManager.updateEvents(Collections.singletonMap(event, null));
        } finally {
            lastStalledByDevice.put(device.getId(), now);
        }
        LOGGER.info("Stalled event created for device {} ({} min receiving data without new coordinates)",
                device.getUniqueId(), (now - lastMove) / 60_000);
    }

    /**
     * Persiste la marca de degradación en BD con el mismo patrón de
     * MobileTelemetryApplier: solo la columna attributes, sin tocar lo demás.
     */
    private void persistDegraded(Device device, boolean degraded) {
        try {
            device.getAttributes().put("mobile.degraded", degraded);
            storage.updateObject(device, new Request(
                    new Columns.Include("attributes"), new Condition.Equals("id", device.getId())));
        } catch (Exception error) {
            LOGGER.warn("Failed to persist mobile.degraded={} for device {}",
                    degraded, device.getId(), error);
        }
    }

    private static String strAttr(Device device, String key) {
        Object v = device.getAttributes().get(key);
        return v != null ? v.toString() : null;
    }
}
