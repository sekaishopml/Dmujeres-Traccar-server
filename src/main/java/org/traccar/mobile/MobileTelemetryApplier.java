package org.traccar.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.json.JSONArray;
import org.traccar.model.Device;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.Set;

/**
 * Aplica la telemetría del canal móvil a los atributos del dispositivo.
 * Extraído de {@link MobileIngestionService} sin cambios de lógica: el
 * orquestador delega aquí tanto en la ruta de presencia como en la de
 * posición (persistida o filtrada).
 */
@Singleton
public class MobileTelemetryApplier {

    /** RTT por encima de este valor (ms) se considera red degradada. */
    public static final int RTT_BAD_MS = 2000;

    /**
     * Causas de pérdida de red que la app puede reportar en el payload
     * ({@code netCause}). Cualquier otro valor se ignora.
     */
    public static final Set<String> NET_CAUSE_WHITELIST = Set.of(
            "ok", "wifi_off_user", "wifi_lost", "mobile_data_off_suspected",
            "mobile_data_off_user", "no_coverage_suspected", "airplane",
            "no_internet", "captive_suspected", "sim_missing");

    /**
     * Certeza anti-trampas reportada por la app ({@code netConf}).
     * Solo {@code confirmed|suspected} se persisten; otro valor se ignora.
     */
    public static final Set<String> NET_CONF_WHITELIST = Set.of("confirmed", "suspected");

    /**
     * Estado de servicio de telefonía reportado por la app ({@code service}).
     * Cualquier otro valor se ignora.
     */
    public static final Set<String> SERVICE_WHITELIST = Set.of(
            "in_service", "out_of_service", "emergency", "unknown");

    private final Storage storage;

    @Inject
    public MobileTelemetryApplier(Storage storage) {
        this.storage = storage;
    }

    /** Actualiza atributos de telemetría del dispositivo sin borrar los existentes. */
    public void applyTelemetry(Device device, JsonNode root) throws Exception {
        if (root == null || !root.hasNonNull("payload")) {
            return;
        }
        JsonNode telemetry = root.path("payload");
        if (telemetry.hasNonNull("pending")) {
            device.getAttributes().put("mobile.pending", telemetry.get("pending").asLong());
        }
        if (telemetry.hasNonNull("battery")) {
            int battery = telemetry.get("battery").asInt();
            device.getAttributes().put("mobile.battery", battery);
            Object existing = device.getAttributes().get("mobile.batteryHistory");
            JSONArray history;
            if (existing instanceof String && !((String) existing).isBlank()) {
                try {
                    history = new JSONArray((String) existing);
                } catch (Exception error) {
                    history = new JSONArray();
                }
            } else {
                history = new JSONArray();
            }
            long nowSeconds = System.currentTimeMillis() / 1000;
            if (history.length() > 0) {
                JSONArray last = history.optJSONArray(history.length() - 1);
                if (last != null && nowSeconds - last.optLong(0) < 60) {
                    history.remove(history.length() - 1);
                }
            }
            JSONArray sample = new JSONArray();
            sample.put(nowSeconds);
            sample.put(battery);
            history.put(sample);
            while (history.length() > 100) {
                history.remove(0);
            }
            device.getAttributes().put("mobile.batteryHistory", history.toString());
        }
        if (telemetry.hasNonNull("network")) {
            device.getAttributes().put("mobile.network", telemetry.get("network").asText());
        }
        if (telemetry.hasNonNull("vendor")) {
            device.getAttributes().put("mobile.vendor", telemetry.get("vendor").asText());
        }
        if (telemetry.hasNonNull("model")) {
            device.getAttributes().put("mobile.model", telemetry.get("model").asText());
        }
        if (telemetry.hasNonNull("appVersion")) {
            device.getAttributes().put("mobile.appVersion", telemetry.get("appVersion").asText());
        }
        if (telemetry.hasNonNull("gps")) {
            device.getAttributes().put("mobile.gps", telemetry.get("gps").asText());
        }
        if (telemetry.hasNonNull("journeyId")) {
            device.getAttributes().put("mobile.journeyId", telemetry.get("journeyId").asLong());
        }
        if (telemetry.hasNonNull("rttMs")) {
            device.getAttributes().put("mobile.rttMs", telemetry.get("rttMs").asLong());
        }
        if (telemetry.hasNonNull("signal")) {
            device.getAttributes().put("mobile.signal", telemetry.get("signal").asInt());
        }
        // Causa de pérdida de red reportada por la app (ver NET_CAUSE_WHITELIST).
        // Cada clave solo se persiste si viene en el payload; mobile.causeAt
        // (epoch ms del momento de recepción) solo se toca si vino al menos
        // uno de estos campos.
        boolean causeTouched = false;
        if (telemetry.hasNonNull("netCause")) {
            String candidate = telemetry.get("netCause").asText();
            if (NET_CAUSE_WHITELIST.contains(candidate)) {
                device.getAttributes().put("mobile.netCause", candidate);
                causeTouched = true;
            }
        }
        if (telemetry.hasNonNull("validated")) {
            device.getAttributes().put("mobile.validated", telemetry.get("validated").asBoolean());
            causeTouched = true;
        }
        if (telemetry.hasNonNull("wifiEnabled")) {
            device.getAttributes().put("mobile.wifiEnabled", telemetry.get("wifiEnabled").asBoolean());
            causeTouched = true;
        }
        if (telemetry.hasNonNull("airplane")) {
            device.getAttributes().put("mobile.airplane", telemetry.get("airplane").asBoolean());
            causeTouched = true;
        }
        if (telemetry.hasNonNull("dataEnabled")) {
            device.getAttributes().put("mobile.dataEnabled", telemetry.get("dataEnabled").asBoolean());
            causeTouched = true;
        }
        if (telemetry.hasNonNull("simPresent")) {
            device.getAttributes().put("mobile.simPresent", telemetry.get("simPresent").asBoolean());
            causeTouched = true;
        }
        if (telemetry.hasNonNull("service")) {
            String candidate = telemetry.get("service").asText();
            if (SERVICE_WHITELIST.contains(candidate)) {
                device.getAttributes().put("mobile.service", candidate);
                causeTouched = true;
            }
        }
        if (telemetry.hasNonNull("netConf")) {
            String candidate = telemetry.get("netConf").asText();
            if (NET_CONF_WHITELIST.contains(candidate)) {
                device.getAttributes().put("mobile.netConf", candidate);
                causeTouched = true;
            }
        }
        // Contadores de captura de la app (observabilidad: distinguen "GPS
        // apagado" de "filtro mata todo" sin acceso al teléfono).
        if (telemetry.hasNonNull("fixReceived")) {
            device.getAttributes().put("mobile.fixReceived", telemetry.get("fixReceived").asLong());
        }
        if (telemetry.hasNonNull("fixRejected")) {
            device.getAttributes().put("mobile.fixRejected", telemetry.get("fixRejected").asLong());
        }
        if (telemetry.hasNonNull("fixEnqueued")) {
            device.getAttributes().put("mobile.fixEnqueued", telemetry.get("fixEnqueued").asLong());
        }
        if (telemetry.hasNonNull("permFine")) {
            device.getAttributes().put("mobile.permFine", telemetry.get("permFine").asBoolean());
        }
        if (telemetry.hasNonNull("permBackground")) {
            device.getAttributes().put("mobile.permBackground", telemetry.get("permBackground").asBoolean());
        }
        if (telemetry.hasNonNull("gpsEnabled")) {
            device.getAttributes().put("mobile.gpsEnabled", telemetry.get("gpsEnabled").asBoolean());
        }
        // Estado GNSS y polling activo (observabilidad GPS, app 1.0.59+).
        if (telemetry.hasNonNull("gnssUsed")) {
            device.getAttributes().put("mobile.gnssUsed", telemetry.get("gnssUsed").asLong());
        }
        if (telemetry.hasNonNull("gnssTotal")) {
            device.getAttributes().put("mobile.gnssTotal", telemetry.get("gnssTotal").asLong());
        }
        if (telemetry.hasNonNull("pollActive")) {
            device.getAttributes().put("mobile.pollActive", telemetry.get("pollActive").asBoolean());
        }
        if (causeTouched) {
            device.getAttributes().put("mobile.causeAt", System.currentTimeMillis());
        }
        // Datos fluyendo sanos → limpiar la marca de degradación (si los datos
        // llegan por presencia y la red responde, el dispositivo no está degradado).
        // Un netCause "ok" con telemetría sana cae en este mismo caso.
        String network = telemetry.hasNonNull("network")
                ? telemetry.get("network").asText() : strAttr(device, "mobile.network");
        boolean hasRtt = telemetry.hasNonNull("rttMs");
        long rttMs = hasRtt ? telemetry.get("rttMs").asLong() : 0L;
        if (isHealthyTelemetry(network, hasRtt, rttMs)) {
            device.getAttributes().put("mobile.degraded", false);
        }
        storage.updateObject(device, new Request(
                new Columns.Include("attributes"), new Condition.Equals("id", device.getId())));
    }

    /**
     * Decisión pura de "datos fluyendo sanos": hay red (distinta de none) y el
     * RTT reportado no supera el umbral de degradación (o no se reportó RTT).
     */
    public static boolean isHealthyTelemetry(String network, boolean hasRtt, long rttMs) {
        return network != null && !"none".equals(network) && (!hasRtt || rttMs <= RTT_BAD_MS);
    }

    private static String strAttr(Device device, String key) {
        Object v = device.getAttributes().get(key);
        return v != null ? v.toString() : null;
    }
}
