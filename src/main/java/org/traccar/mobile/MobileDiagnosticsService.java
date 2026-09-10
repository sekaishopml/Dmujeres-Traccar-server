/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.traccar.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.database.NotificationManager;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.ObjectOperation;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Ingesta de diagnósticos del cliente: normaliza el reporte que manda la app, lo deja en el
 * dispositivo como {@code lastDiagnostics} (JSON compacto con SOLO los campos del esquema) +
 * {@code lastDiagnosticsAt} (epoch ms del servidor) y, únicamente cuando hay señal de
 * problema, emite un evento {@code mobileDiagnostics} para que el panel alerte sin spamear.
 *
 * <p>Es un {@code @Singleton} a propósito: el recurso Jersey se instancia por request, así
 * que el mapa del rate-limit (20 s por dispositivo) tiene que vivir aquí, igual que los
 * cooldowns de {@link MobileSilenceMonitor}.</p>
 *
 * <p>Presupuestos: body de 10 000 bytes, cadena normalizada de 4096 caracteres y —además—
 * la columna {@code tc_devices.attributes} es VARCHAR(4000), así que el JSON se encoge hasta
 * que los atributos COMPLETOS del dispositivo sigan cabiendo (ver {@code attributeBudget}).
 * Si no se hiciera así, el UPDATE fallaría y se perdería toda la telemetría móvil del
 * dispositivo, no solo el diagnóstico.</p>
 */
@Singleton
public class MobileDiagnosticsService {

    public enum Outcome {
        /** Persistido (y evento emitido si había señal de problema). */
        ACCEPTED,
        /** Ignorado: llegó antes de 20 s del último reporte aceptado del mismo dispositivo. */
        THROTTLED,
        /** Body mayor que MAX_BODY_BYTES. */
        TOO_LARGE,
        /** JSON ausente, roto o que no es un objeto. */
        INVALID,
        /** El {@code deviceId} del body no coincide con el dispositivo autenticado. */
        DEVICE_MISMATCH,
        /** Falló la escritura de atributos (o ya no cabe nada en la columna). */
        STORAGE_ERROR
    }

    public static final int MAX_BODY_BYTES = 10_000;
    public static final int MAX_DIAGNOSTICS_CHARS = 4096;
    public static final long THROTTLE_WINDOW_MS = 20_000L;

    /** Clave del atributo con el JSON compacto en el dispositivo. */
    public static final String ATTRIBUTE_DIAGNOSTICS = "lastDiagnostics";
    /** Clave del epoch ms (reloj del servidor) de la última ingesta aceptada. */
    public static final String ATTRIBUTE_DIAGNOSTICS_AT = "lastDiagnosticsAt";

    /** VARCHAR(4000) de tc_devices.attributes; se deja margen por serialización. */
    private static final int ATTRIBUTE_COLUMN_CHARS = 4000;
    private static final int ATTRIBUTE_SLACK_CHARS = 96;
    /** Por debajo de este presupuesto no se escribe: {@code {}} no aporta nada. */
    private static final int MIN_DIAGNOSTICS_CHARS = 64;
    private static final int MAX_STRING_CHARS = 64;
    private static final int MAX_TRACKED_DEVICES = 5000;
    private static final int MAX_COUNTER = 1_000_000;
    private static final long MAX_SPAN_MS = 31 * 24 * 3600_000L;

    /**
     * Orden de descarte para encoger el JSON (lo menos útil primero) hasta que cabe en el
     * presupuesto. {@code ts} es lo último en sobrevivir.
     */
    private static final String[][] DROP_ORDER = {
            {"report", "health", "lastStartError"},
            {"report", "app", "versionName"},
            {"report", "net", "cause"},
            {"report", "buffer", "policy"},
            {"report", "mqtt", "status"},
            {"report", "journey"},
            {"report", "app"},
            {"report", "power"},
            {"report", "net"},
            {"report", "buffer"},
            {"report", "mqtt"},
            {"report", "health"},
            {"deviceId"},
            {"report"},
    };

    private static final Pattern WHITESPACE = Pattern.compile("[\\p{Cntrl}\\s]+");
    private static final JsonNodeFactory NODE = JsonNodeFactory.instance;
    private static final Logger LOGGER = LoggerFactory.getLogger(MobileDiagnosticsService.class);

    private final Storage storage;
    private final ObjectMapper mapper;
    private final NotificationManager notificationManager;
    private final CacheManager cacheManager;

    private final ConcurrentHashMap<Long, Long> lastAcceptedByDevice = new ConcurrentHashMap<>();

    @Inject
    public MobileDiagnosticsService(
            Storage storage, ObjectMapper mapper, NotificationManager notificationManager,
            CacheManager cacheManager) {
        this.storage = storage;
        this.mapper = mapper;
        this.notificationManager = notificationManager;
        this.cacheManager = cacheManager;
    }

    public Outcome ingest(Device device, String body) {
        return ingest(device, body, System.currentTimeMillis());
    }

    /**
     * Procesa un reporte. El rate-limit se evalúa ANTES de parsear (es lo barato) y el
     * instante solo se apunta cuando la escritura en BD tuvo éxito, para que un 400/500 no
     * silencie los reintentos del cliente.
     */
    public Outcome ingest(Device device, String body, long nowMs) {
        long deviceId = device.getId();
        Long last = lastAcceptedByDevice.get(deviceId);
        if (last != null && nowMs - last < THROTTLE_WINDOW_MS) {
            return Outcome.THROTTLED;
        }
        if (body == null || body.isBlank()) {
            return Outcome.INVALID;
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            return Outcome.TOO_LARGE;
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception error) {
            LOGGER.debug("Malformed mobile diagnostics payload", error);
            return Outcome.INVALID;
        }
        if (root == null || !root.isObject()) {
            return Outcome.INVALID;
        }
        Long reported = asLong(root.get("deviceId"));
        if (reported != null && reported.longValue() != deviceId) {
            LOGGER.warn("Diagnostics deviceId mismatch: authenticated device {} vs body {}", deviceId, reported);
            return Outcome.DEVICE_MISMATCH;
        }

        ObjectNode normalized = normalize(root, nowMs);
        // La decisión y el payload del evento se capturan ANTES de encoger el JSON:
        // fit() descarta campos in situ y una señal de alerta no puede perderse porque el
        // reporte sea largo.
        Event event = shouldAlert(normalized) ? buildEvent(device, normalized) : null;

        int budget = attributeBudget(device);
        if (budget < MIN_DIAGNOSTICS_CHARS) {
            LOGGER.warn("No room left in attributes for diagnostics on device {} (budget {} chars)",
                    deviceId, budget);
            return Outcome.STORAGE_ERROR;
        }
        String json;
        try {
            json = fit(mapper.writeValueAsString(normalized), normalized, budget);
        } catch (Exception error) {
            LOGGER.warn("Failed to normalize mobile diagnostics for device {}", deviceId, error);
            return Outcome.INVALID;
        }

        try {
            persist(device, json, nowMs);
        } catch (Exception error) {
            LOGGER.warn("Failed to persist mobile diagnostics for device {}", deviceId, error);
            return Outcome.STORAGE_ERROR;
        }

        lastAcceptedByDevice.put(deviceId, nowMs);
        pruneThrottleMap(nowMs);

        if (event != null) {
            emit(device, event);
        }
        return Outcome.ACCEPTED;
    }

    // --------------------------------------------------------------------------------------
    // Normalización: whitelist estricto, todo lo que no está en el esquema se descarta
    // --------------------------------------------------------------------------------------

    /**
     * Reconstruye el reporte dejando solo las claves del esquema. Los números se aceptan como
     * double y se truncan a long/int; las cadenas se limpian (fuera control chars, espacios
     * colapsados, recortadas a 64 caracteres) y los campos "enum-ish" (status, policy, cause)
     * se guardan en minúsculas.
     */
    static ObjectNode normalize(JsonNode root, long nowMs) {
        ObjectNode out = NODE.objectNode();
        Long deviceId = asLong(root.get("deviceId"));
        if (deviceId != null) {
            out.put("deviceId", deviceId);
        }
        Long ts = asLong(root.get("ts"));
        out.put("ts", ts != null ? ts : nowMs);

        JsonNode report = root.get("report");
        if (report != null && report.isObject()) {
            ObjectNode groups = NODE.objectNode();

            JsonNode app = report.path("app");
            ObjectNode appNode = NODE.objectNode();
            copyInt(app, appNode, "versionCode", 0, Integer.MAX_VALUE);
            copyString(app, appNode, "versionName", false);
            putIfNotEmpty(groups, "app", appNode);

            JsonNode journey = report.path("journey");
            ObjectNode journeyNode = NODE.objectNode();
            copyBool(journey, journeyNode, "active");
            copyLong(journey, journeyNode, "elapsedMs", 0, MAX_SPAN_MS);
            copyLong(journey, journeyNode, "startAt", 0, Long.MAX_VALUE);
            putIfNotEmpty(groups, "journey", journeyNode);

            JsonNode buffer = report.path("buffer");
            ObjectNode bufferNode = NODE.objectNode();
            copyInt(buffer, bufferNode, "pending", 0, MAX_COUNTER);
            copyInt(buffer, bufferNode, "max", 0, MAX_COUNTER);
            copyString(buffer, bufferNode, "policy", true);
            putIfNotEmpty(groups, "buffer", bufferNode);

            JsonNode mqtt = report.path("mqtt");
            ObjectNode mqttNode = NODE.objectNode();
            copyString(mqtt, mqttNode, "status", true);
            copyLong(mqtt, mqttNode, "lastAckAt", 0, Long.MAX_VALUE);
            copyLong(mqtt, mqttNode, "lastFixAt", 0, Long.MAX_VALUE);
            copyInt(mqtt, mqttNode, "reconnects", 0, MAX_COUNTER);
            putIfNotEmpty(groups, "mqtt", mqttNode);

            JsonNode net = report.path("net");
            ObjectNode netNode = NODE.objectNode();
            copyString(net, netNode, "cause", true);
            copyBool(net, netNode, "cellular");
            copyBool(net, netNode, "airplane");
            putIfNotEmpty(groups, "net", netNode);

            JsonNode power = report.path("power");
            ObjectNode powerNode = NODE.objectNode();
            copyInt(power, powerNode, "battery", -1, 100);
            copyBool(power, powerNode, "exempt");
            copyLong(power, powerNode, "idleMs", 0, MAX_SPAN_MS);
            putIfNotEmpty(groups, "power", powerNode);

            JsonNode health = report.path("health");
            ObjectNode healthNode = NODE.objectNode();
            copyInt(health, healthNode, "crashes24h", 0, MAX_COUNTER);
            copyInt(health, healthNode, "anrs24h", 0, MAX_COUNTER);
            copyInt(health, healthNode, "stuckStops", 0, MAX_COUNTER);
            copyInt(health, healthNode, "clockSteps24h", 0, MAX_COUNTER);
            copyString(health, healthNode, "lastStartError", false);
            putIfNotEmpty(groups, "health", healthNode);

            if (!groups.isEmpty()) {
                out.set("report", groups);
            }
        }
        return out;
    }

    private static void putIfNotEmpty(ObjectNode parent, String key, ObjectNode child) {
        if (!child.isEmpty()) {
            parent.set(key, child);
        }
    }

    /** Serializa respetando el presupuesto, descartando campos por {@link #DROP_ORDER}. */
    private String fit(String json, ObjectNode normalized, int budget) throws Exception {
        if (json.length() <= budget) {
            return json;
        }
        for (String[] path : DROP_ORDER) {
            remove(normalized, path);
            json = mapper.writeValueAsString(normalized);
            if (json.length() <= budget) {
                return json;
            }
        }
        return "{}";
    }

    private static void remove(ObjectNode root, String[] path) {
        ObjectNode parent = root;
        for (int i = 0; i < path.length - 1 && parent != null; i++) {
            JsonNode child = parent.get(path[i]);
            parent = child != null && child.isObject() ? (ObjectNode) child : null;
        }
        if (parent != null) {
            parent.remove(path[path.length - 1]);
        }
    }

    /** Cuánto puede medir la cadena de diagnóstico sin desbordar la columna attributes. */
    private int attributeBudget(Device device) {
        try {
            Map<String, Object> others = new LinkedHashMap<>(device.getAttributes());
            others.remove(ATTRIBUTE_DIAGNOSTICS);
            others.remove(ATTRIBUTE_DIAGNOSTICS_AT);
            int base = mapper.writeValueAsString(others).length();
            return Math.max(0, Math.min(
                    MAX_DIAGNOSTICS_CHARS, ATTRIBUTE_COLUMN_CHARS - ATTRIBUTE_SLACK_CHARS - base));
        } catch (Exception error) {
            LOGGER.debug("Failed to estimate attributes size for device {}", device.getId(), error);
            return MAX_DIAGNOSTICS_CHARS;
        }
    }

    // --------------------------------------------------------------------------------------
    // Señal de alerta: poco volumen, solo problemas
    // --------------------------------------------------------------------------------------

    /**
     * Criterio puro del evento: caídas, parones atrapados, saltos de reloj o MQTT caído.
     * Los ANRs no disparan alerta (quedan en el atributo para consultarlos desde el panel).
     */
    public static boolean shouldAlert(JsonNode normalized) {
        JsonNode health = normalized.path("report").path("health");
        JsonNode mqtt = normalized.path("report").path("mqtt");
        return health.path("crashes24h").asInt(0) > 0
                || health.path("stuckStops").asInt(0) > 0
                || health.path("clockSteps24h").asInt(0) > 0
                || "down".equalsIgnoreCase(mqtt.path("status").asText(""));
    }

    private static Event buildEvent(Device device, ObjectNode normalized) {
        JsonNode health = normalized.path("report").path("health");
        JsonNode power = normalized.path("report").path("power");
        JsonNode mqtt = normalized.path("report").path("mqtt");
        Event event = new Event(Event.TYPE_MOBILE_DIAGNOSTICS, device.getId());
        event.getAttributes().put("mobileSeverity", "warning");
        event.getAttributes().put("crashes24h", health.path("crashes24h").asInt(0));
        event.getAttributes().put("anrs24h", health.path("anrs24h").asInt(0));
        event.getAttributes().put("stuckStops", health.path("stuckStops").asInt(0));
        event.getAttributes().put("clockSteps24h", health.path("clockSteps24h").asInt(0));
        if (mqtt.has("status")) {
            event.getAttributes().put("mqttStatus", mqtt.get("status").asText());
        }
        if (power.has("battery")) {
            event.getAttributes().put("battery", power.get("battery").asInt());
        }
        return event;
    }

    /**
     * Publicar por {@link NotificationManager} con posición nula: es el mismo camino que
     * usan ConnectionManager (deviceOnline/Offline) y MobileSilenceMonitor, y tc_events
     * admite positionid NULL.
     */
    private void emit(Device device, Event event) {
        try {
            notificationManager.updateEvents(Collections.singletonMap(event, null));
        } catch (Exception error) {
            LOGGER.warn("Failed to emit mobileDiagnostics event for device {}", device.getId(), error);
        }
    }

    // --------------------------------------------------------------------------------------
    // Persistencia: merge de atributos sobre el Device cargado (nunca se pisan los existentes)
    // --------------------------------------------------------------------------------------

    private void persist(Device device, String json, long nowMs) throws Exception {
        long deviceId = device.getId();
        device.getAttributes().put(ATTRIBUTE_DIAGNOSTICS, json);
        device.getAttributes().put(ATTRIBUTE_DIAGNOSTICS_AT, nowMs);
        storage.updateObject(device, new Request(
                new Columns.Include("attributes"), new Condition.Equals("id", deviceId)));
        try {
            cacheManager.invalidateObject(true, Device.class, deviceId, ObjectOperation.UPDATE);
        } catch (Exception error) {
            // El atributo ya está en BD; una caché desactualizada se resuelve al recargar.
            LOGGER.debug("Failed to invalidate device cache after diagnostics", error);
        }
    }

    // --------------------------------------------------------------------------------------
    // Rate-limit en memoria (mismo estilo que los cooldowns de MobileSilenceMonitor)
    // --------------------------------------------------------------------------------------

    /** Visible para pruebas: si el instante cae dentro de la ventana del último aceptado. */
    public boolean isThrottled(long deviceId, long nowMs) {
        Long last = lastAcceptedByDevice.get(deviceId);
        return last != null && nowMs - last < THROTTLE_WINDOW_MS;
    }

    private void pruneThrottleMap(long nowMs) {
        if (lastAcceptedByDevice.size() > MAX_TRACKED_DEVICES) {
            lastAcceptedByDevice.entrySet().removeIf(
                    entry -> nowMs - entry.getValue() >= THROTTLE_WINDOW_MS);
        }
    }

    // --------------------------------------------------------------------------------------
    // Coerción defensiva: double -> long, cadenas limpias, ninguna excepción al cliente
    // --------------------------------------------------------------------------------------

    private static void copyInt(JsonNode from, ObjectNode to, String key, int min, int max) {
        Long value = asLong(from.get(key));
        if (value != null) {
            long clamped = Math.max(min, Math.min(max, value.longValue()));
            to.put(key, (int) clamped);
        }
    }

    private static void copyLong(JsonNode from, ObjectNode to, String key, long min, long max) {
        Long value = asLong(from.get(key));
        if (value != null) {
            to.put(key, Math.max(min, Math.min(max, value.longValue())));
        }
    }

    private static void copyBool(JsonNode from, ObjectNode to, String key) {
        Boolean value = asBoolean(from.get(key));
        if (value != null) {
            to.put(key, value.booleanValue());
        }
    }

    private static void copyString(JsonNode from, ObjectNode to, String key, boolean lowercase) {
        String value = asString(from.get(key), lowercase);
        if (value != null) {
            to.put(key, value);
        }
    }

    /** Acepta integrales, doubles (truncados) y cadenas numéricas; el resto se ignora. */
    static Long asLong(JsonNode node) {
        if (node == null || node.isNull() || node.isContainerNode() || node.isBoolean()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.longValue();
        }
        if (node.isFloatingPointNumber() || node.isBigDecimal()) {
            double value = node.doubleValue();
            return Double.isFinite(value) ? (long) value : null;
        }
        String text = node.asText(null);
        if (text == null) {
            return null;
        }
        text = text.trim();
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException integerValueError) {
            try {
                double value = Double.parseDouble(text);
                return Double.isFinite(value) ? (long) value : null;
            } catch (NumberFormatException error) {
                return null;
            }
        }
    }

    /** Solo valores escalares; objetos y arrays se descartan. */
    static String asString(JsonNode node, boolean lowercase) {
        if (node == null || node.isNull() || node.isContainerNode()) {
            return null;
        }
        String text = node.isNumber() || node.isBoolean() ? node.asText() : node.textValue();
        if (text == null) {
            return null;
        }
        String cleaned = WHITESPACE.matcher(text).replaceAll(" ").trim();
        if (lowercase) {
            cleaned = cleaned.toLowerCase(Locale.ROOT);
        }
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > MAX_STRING_CHARS ? cleaned.substring(0, MAX_STRING_CHARS) : cleaned;
    }

    static Boolean asBoolean(JsonNode node) {
        if (node == null || node.isNull() || node.isContainerNode()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.asLong() != 0L;
        }
        String text = node.asText(null);
        if (text == null) {
            return null;
        }
        String cleaned = text.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(cleaned) || "1".equals(cleaned)) {
            return Boolean.TRUE;
        }
        if ("false".equals(cleaned) || "0".equals(cleaned)) {
            return Boolean.FALSE;
        }
        return null;
    }
}
