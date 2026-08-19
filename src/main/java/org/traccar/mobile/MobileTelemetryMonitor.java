package org.traccar.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import org.traccar.database.NotificationManager;
import org.traccar.model.Device;
import org.traccar.model.Event;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Compara el estado de telemetría anterior vs el nuevo después de applyTelemetry
 * y genera eventos para cambios relevantes: GPS on/off, red, batería crítica.
 * Solo registra acciones manuales del colaborador, no errores del dispositivo.
 */
public final class MobileTelemetryMonitor {

    private static final int BATTERY_CRITICAL_THRESHOLD = 10;

    private MobileTelemetryMonitor() {
    }

    /**
     * Captura el estado actual del dispositivo antes de applyTelemetry.
     */
    public static TelemetrySnapshot capture(Device device) {
        Map<String, Object> attr = device.getAttributes();
        return new TelemetrySnapshot(
                str(attr, "mobile.gps"),
                str(attr, "mobile.network"),
                intVal(attr, "mobile.battery"),
                longVal(attr, "mobile.journeyId")
        );
    }

    /**
     * Genera eventos si el estado de telemetría cambió entre snapshot y el device actual.
     * Llamar DESPUÉS de applyTelemetry.
     */
    public static List<Event> detectChanges(
            Device device,
            TelemetrySnapshot before,
            JsonNode root
    ) {
        List<Event> events = new ArrayList<>();
        Map<String, Object> attr = device.getAttributes();
        long deviceId = device.getId();

        String gpsNew = str(attr, "mobile.gps");
        String netNew = str(attr, "mobile.network");
        int batteryNew = intVal(attr, "mobile.battery");

        // GPS on/off
        if (before.gps != null && gpsNew != null && !before.gps.equals(gpsNew)) {
            if ("off".equals(gpsNew)) {
                events.add(warningEvent(Event.TYPE_MOBILE_GPS_DISABLED, deviceId, batteryNew, gpsNew, netNew));
            } else if ("on".equals(gpsNew)) {
                events.add(infoEvent(Event.TYPE_MOBILE_GPS_REENABLED, deviceId, batteryNew, gpsNew, netNew));
            }
        }

        // Red on/off
        if (before.network != null && netNew != null && !before.network.equals(netNew)) {
            if ("none".equals(netNew)) {
                events.add(warningEvent(Event.TYPE_MOBILE_NETWORK_LOST, deviceId, batteryNew, gpsNew, netNew));
            } else if (!"none".equals(before.network) || "none".equals(before.network)) {
                // Solo registrar restauración si antes había red y ahora no, o viceversa
                if (!"none".equals(netNew) && "none".equals(before.network)) {
                    events.add(infoEvent(Event.TYPE_MOBILE_NETWORK_RESTORED, deviceId, batteryNew, gpsNew, netNew));
                }
            }
        }

        // Batería crítica (solo al bajar de 10%)
        if (before.battery > BATTERY_CRITICAL_THRESHOLD
                && batteryNew <= BATTERY_CRITICAL_THRESHOLD
                && batteryNew >= 0) {
            events.add(warningEvent(Event.TYPE_MOBILE_BATTERY_CRITICAL, deviceId, batteryNew, gpsNew, netNew));
        }

        // Jornada iniciada/finalizada (desde presence payload)
        if (root != null && root.hasNonNull("payload")) {
            JsonNode payload = root.path("payload");
            if (payload.hasNonNull("journeyStarted") && payload.get("journeyStarted").asBoolean()) {
                events.add(infoEvent(Event.TYPE_MOBILE_JOURNEY_STARTED, deviceId, batteryNew, gpsNew, netNew));
            }
            if (payload.hasNonNull("journeyEnded") && payload.get("journeyEnded").asBoolean()) {
                events.add(infoEvent(Event.TYPE_MOBILE_JOURNEY_ENDED, deviceId, batteryNew, gpsNew, netNew));
            }
        }

        return events;
    }

    private static Event warningEvent(String type, long deviceId, int battery, String gps, String network) {
        Event event = new Event(type, deviceId);
        event.getAttributes().put("mobileSeverity", "warning");
        addCommonAttrs(event, battery, gps, network);
        return event;
    }

    private static Event infoEvent(String type, long deviceId, int battery, String gps, String network) {
        Event event = new Event(type, deviceId);
        event.getAttributes().put("mobileSeverity", "info");
        addCommonAttrs(event, battery, gps, network);
        return event;
    }

    private static void addCommonAttrs(Event event, int battery, String gps, String network) {
        event.getAttributes().put("battery", battery);
        if (gps != null) event.getAttributes().put("gps", gps);
        if (network != null) event.getAttributes().put("network", network);
    }

    private static String str(Map<String, Object> attr, String key) {
        Object v = attr.get(key);
        return v != null ? v.toString() : null;
    }

    private static int intVal(Map<String, Object> attr, String key) {
        Object v = attr.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return -1; }
    }

    private static long longVal(Map<String, Object> attr, String key) {
        Object v = attr.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return 0L; }
    }

    public record TelemetrySnapshot(String gps, String network, int battery, long journeyId) {}
}
