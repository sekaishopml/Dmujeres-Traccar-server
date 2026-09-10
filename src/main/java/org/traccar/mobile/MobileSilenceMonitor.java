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

import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Monitor de silencio: detecta dispositivos móviles con jornada activa que dejaron de
 * comunicarse. Según el último estado de red conocido, crea:
 * - mobileNetworkLost: si la última red fue wifi o mobile (el teléfono perdió conexión)
 * - mobilePossiblePowerOff: si la última red era none o no hay datos (teléfono apagado)
 *
 * Threshold: 2 minutos (el teléfono no puede avisar que se quedó sin red,
 * así que el servidor lo detecta por silencio).
 */
@Singleton
public class MobileSilenceMonitor implements LifecycleObject {

    private static final Logger LOGGER = LoggerFactory.getLogger(MobileSilenceMonitor.class);
    private static final long SILENCE_THRESHOLD_MS = 2 * 60_000L;
    private static final long COOLDOWN_MS = 15 * 60_000L;
    /** Jornada activa con mensajes pero sin coordenadas nuevas >= 15 min. */
    private static final long STALLED_THRESHOLD_MS = 15 * 60_000L;

    private final Storage storage;
    private final NotificationManager notificationManager;
    private final MobileJourneyRegistry journeyRegistry;
    private final MobileQualityFilter qualityFilter;
    private ScheduledExecutorService scheduler;

    private final ConcurrentHashMap<Long, Long> lastEventByDevice = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> lastStalledByDevice = new ConcurrentHashMap<>();

    @Inject
    public MobileSilenceMonitor(Storage storage, NotificationManager notificationManager,
            MobileJourneyRegistry journeyRegistry, MobileQualityFilter qualityFilter) {
        this.storage = storage;
        this.notificationManager = notificationManager;
        this.journeyRegistry = journeyRegistry;
        this.qualityFilter = qualityFilter;
    }

    @Override
    public synchronized void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MobileSilenceMonitor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::check, 30, 30, TimeUnit.SECONDS);
        LOGGER.info("Mobile silence monitor started (threshold={}s, cooldown={}s)",
                SILENCE_THRESHOLD_MS / 1000, COOLDOWN_MS / 1000);
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
            // Solo consultar los dispositivos con jornada activa (registry en memoria),
            // no cargar toda la tabla tc_devices cada ciclo.
            for (Long deviceId : journeyRegistry.activeDeviceIds()) {
                Device device;
                try {
                    device = storage.getObject(Device.class, new Request(
                            new Columns.Include("id", "uniqueId", "status", "lastUpdate", "attributes"),
                            new Condition.Equals("id", deviceId)));
                } catch (Exception lookupError) {
                    LOGGER.warn("Silence monitor: failed to load device {}", deviceId, lookupError);
                    continue;
                }
                if (device == null || device.getLastUpdate() == null) continue;

                long lastUpdateMs = device.getLastUpdate().getTime();
                if (now - lastUpdateMs < SILENCE_THRESHOLD_MS) {
                    // Recuperación: si antes se disparó un evento de silencio para este
                    // dispositivo y ya volvieron los datos frescos, limpiar la degradación.
                    if (lastEventByDevice.remove(deviceId) != null) {
                        persistDegraded(device, false);
                    }
                    checkStalled(device, now);
                    continue;
                }

                Long lastEvent = lastEventByDevice.get(deviceId);
                if (lastEvent != null && now - lastEvent < COOLDOWN_MS) continue;

                int battery = intAttr(device, "mobile.battery");
                String gps = strAttr(device, "mobile.gps");
                String network = strAttr(device, "mobile.network");

                // Determinar tipo de evento según la última red conocida:
                // - Si la última red fue wifi o mobile → el teléfono tenía conexión y la perdió
                // - Si la última red era none o no hay datos → posible apagado o sin señal desde hace rato
                String eventType;
                if (network != null && !"none".equals(network)) {
                    eventType = Event.TYPE_MOBILE_NETWORK_LOST;
                } else {
                    eventType = Event.TYPE_MOBILE_POSSIBLE_POWER_OFF;
                }

                Event event = new Event(eventType, deviceId);
                event.getAttributes().put("mobileSeverity", "warning");
                event.getAttributes().put("lastBattery", battery);
                event.getAttributes().put("silenceMinutes", (now - lastUpdateMs) / 60_000);
                if (gps != null) event.getAttributes().put("gps", gps);
                if (network != null) event.getAttributes().put("network", network);

                try {
                    notificationManager.updateEvents(Collections.singletonMap(event, null));
                } finally {
                    lastEventByDevice.put(deviceId, now);
                }
                // Dispositivo con jornada activa en silencio → marcar degradado en BD
                // (la recuperación se persiste al volver datos frescos o por una
                // presencia sana vía MobileTelemetryApplier).
                persistDegraded(device, true);
                LOGGER.info("Silence event created for device {} type={} ({} min, battery={}%, network={})",
                        device.getUniqueId(), eventType, (now - lastUpdateMs) / 60_000, battery, network);
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

    private static int intAttr(Device device, String key) {
        Object v = device.getAttributes().get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return -1; }
    }
}
