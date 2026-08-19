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
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Monitor de silencio: detecta dispositivos móviles con jornada activa que dejaron de
 * comunicarse. Genera un evento "Posible teléfono apagado o sin conexión" después de 5
 * minutos de silencio. Solo crea una vez por episodio (no genera spam).
 */
@Singleton
public class MobileSilenceMonitor implements LifecycleObject {

    private static final Logger LOGGER = LoggerFactory.getLogger(MobileSilenceMonitor.class);
    private static final long SILENCE_THRESHOLD_MS = 5 * 60_000L;
    private static final long COOLDOWN_MS = 30 * 60_000L;

    private final Storage storage;
    private final NotificationManager notificationManager;
    private ScheduledExecutorService scheduler;

    /** Último evento creado por dispositivo para evitar spam. */
    private final ConcurrentHashMap<Long, Long> lastEventByDevice = new ConcurrentHashMap<>();

    @Inject
    public MobileSilenceMonitor(Storage storage, NotificationManager notificationManager) {
        this.storage = storage;
        this.notificationManager = notificationManager;
    }

    @Override
    public synchronized void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MobileSilenceMonitor");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::check, 60, 60, TimeUnit.SECONDS);
        LOGGER.info("Mobile silence monitor started");
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void check() {
        try {
            List<Device> devices = storage.getObjects(Device.class, new Request(
                    new Columns.Include("id", "uniqueId", "status", "lastUpdate", "attributes")));
            long now = System.currentTimeMillis();
            for (Device device : devices) {
                if (device.getLastUpdate() == null) continue;

                // Solo dispositivos con jornada activa (journeyId en atributos vía telemetry)
                Object journeyIdObj = device.getAttributes().get("mobile.journeyId");
                if (journeyIdObj == null) continue;
                long journeyId = 0L;
                if (journeyIdObj instanceof Number) {
                    journeyId = ((Number) journeyIdObj).longValue();
                } else {
                    try { journeyId = Long.parseLong(journeyIdObj.toString()); } catch (Exception ignored) {}
                }
                if (journeyId <= 0L) continue;

                long lastUpdateMs = device.getLastUpdate().getTime();
                if (now - lastUpdateMs < SILENCE_THRESHOLD_MS) continue;

                // Cooldown: no crear el mismo tipo de evento en los últimos 30 minutos
                Long lastEvent = lastEventByDevice.get(device.getId());
                if (lastEvent != null && now - lastEvent < COOLDOWN_MS) continue;

                // Determinar severidad según última batería conocida
                int battery = -1;
                Object batObj = device.getAttributes().get("mobile.battery");
                if (batObj instanceof Number) battery = ((Number) batObj).intValue();
                else try { battery = Integer.parseInt(batObj.toString()); } catch (Exception ignored) {}

                Event event = new Event(Event.TYPE_MOBILE_POSSIBLE_POWER_OFF, device.getId());
                event.getAttributes().put("mobileSeverity", "warning");
                event.getAttributes().put("lastBattery", battery);
                event.getAttributes().put("silenceMinutes", (now - lastUpdateMs) / 60_000);
                String gps = null;
                Object gpsObj = device.getAttributes().get("mobile.gps");
                if (gpsObj != null) gps = gpsObj.toString();
                if (gps != null) event.getAttributes().put("gps", gps);
                String network = null;
                Object netObj = device.getAttributes().get("mobile.network");
                if (netObj != null) network = netObj.toString();
                if (network != null) event.getAttributes().put("network", network);

                notificationManager.updateEvents(java.util.Collections.singletonMap(event, null));
                lastEventByDevice.put(device.getId(), now);
                LOGGER.info("Silence event created for device {} ({} min, battery {}%)",
                        device.getUniqueId(), (now - lastUpdateMs) / 60_000, battery);
            }
        } catch (Exception error) {
            LOGGER.warn("Mobile silence monitor check failed", error);
        }
    }
}
