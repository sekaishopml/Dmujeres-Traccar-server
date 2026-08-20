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
import org.traccar.storage.query.Request;

import java.util.Collections;
import java.util.List;
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

    private final Storage storage;
    private final NotificationManager notificationManager;
    private ScheduledExecutorService scheduler;

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
            List<Device> devices = storage.getObjects(Device.class, new Request(
                    new Columns.Include("id", "uniqueId", "status", "lastUpdate", "attributes")));
            long now = System.currentTimeMillis();
            for (Device device : devices) {
                if (device.getLastUpdate() == null) continue;

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

                Long lastEvent = lastEventByDevice.get(device.getId());
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

                Event event = new Event(eventType, device.getId());
                event.getAttributes().put("mobileSeverity", "warning");
                event.getAttributes().put("lastBattery", battery);
                event.getAttributes().put("silenceMinutes", (now - lastUpdateMs) / 60_000);
                if (gps != null) event.getAttributes().put("gps", gps);
                if (network != null) event.getAttributes().put("network", network);

                notificationManager.updateEvents(Collections.singletonMap(event, null));
                lastEventByDevice.put(device.getId(), now);
                LOGGER.info("Silence event created for device {} type={} ({} min, battery={}%, network={})",
                        device.getUniqueId(), eventType, (now - lastUpdateMs) / 60_000, battery, network);
            }
        } catch (Exception error) {
            LOGGER.warn("Mobile silence monitor check failed", error);
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
