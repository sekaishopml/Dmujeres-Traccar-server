package org.traccar.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Device;
import org.traccar.model.ObjectOperation;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FASE 7 (§21): ingesta de snapshots de salud móvil. Valida el contrato,
 * persiste en {@link DeviceHealthStore} de forma idempotente por (deviceId, ts)
 * y deja el último estado en atributos del dispositivo para el dashboard
 * ({@code mobile.healthState}, {@code mobile.healthAt}).
 *
 * El cuerpo puede incluir un bloque {@code device} con el perfil de capacidad
 * (§22): fabricante, modelo, versión de Android/SDK, ROM. Se persiste en
 * atributos {@code mobile.*} para el dashboard OEM y NO se mezcla con tracking.
 *
 * Separación de planos (§5): esto es OBSERVABILIDAD; nunca decide tracking,
 * recovery ni posiciones. Un fallo aquí no puede afectar la ruta.
 */
@Singleton
public class MobileHealthService {

    public static final int MAX_ITEMS = 300;
    public static final int MAX_BODY_BYTES = 262_144;

    /** Un dispositivo no puede reportar salud más de 1 vez cada 10 s (anti spam). */
    public static final long THROTTLE_WINDOW_MS = 10_000L;

    /** Ventana de aceptación del timestamp del snapshot. */
    public static final long MAX_FUTURE_SKEW_MS = 5 * 60_000L;
    public static final long MAX_PAST_AGE_MS = 7L * 24 * 3_600_000L;

    /** Retención de snapshots en servidor (evidencia operativa). */
    public static final long RETENTION_MS = 90L * 24 * 3_600_000L;

    public static final Set<String> EVENT_TYPES = Set.of("HEARTBEAT", "STATE_CHANGE", "CRITICAL", "RECOVERY");
    public static final Set<String> MOTION_VALUES = Set.of("STATIONARY", "MOVING", "UNKNOWN");
    public static final Set<String> NETWORK_VALUES = Set.of("wifi", "mobile", "none", "unknown");
    public static final Set<String> HEALTH_STATES = Set.of(
            "LIVE", "DEGRADED", "SILENT", "RECOVERY", "OFFLINE", "UNKNOWN");

    private static final Logger LOGGER = LoggerFactory.getLogger(MobileHealthService.class);

    private final DeviceHealthStore store;
    private final Storage storage;
    private final ObjectMapper mapper;
    private final CacheManager cacheManager;

    private final ConcurrentHashMap<Long, Long> lastAcceptedByDevice = new ConcurrentHashMap<>();
    private volatile long lastPruneAtMs;

    @Inject
    public MobileHealthService(
            DeviceHealthStore store, Storage storage, ObjectMapper mapper, CacheManager cacheManager) {
        this.store = store;
        this.storage = storage;
        this.mapper = mapper;
        this.cacheManager = cacheManager;
    }

    /** Resultado del ingest: contadores honestos, sin inventar aceptación. */
    public record Outcome(int accepted, int duplicates, int rejected, boolean throttled) {}

    public Outcome ingest(Device device, String body) {
        return ingest(device, body, System.currentTimeMillis());
    }

    public Outcome ingest(Device device, String body, long nowMs) {
        long deviceId = device.getId();
        Long last = lastAcceptedByDevice.get(deviceId);
        if (last != null && nowMs - last < THROTTLE_WINDOW_MS) {
            return new Outcome(0, 0, 0, true);
        }
        if (body == null || body.isBlank()
                || body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            return new Outcome(0, 0, 0, false);
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception error) {
            LOGGER.debug("Malformed mobile health payload", error);
            return new Outcome(0, 0, 0, false);
        }
        JsonNode snapshots = root != null && root.isObject() ? root.path("snapshots") : null;
        if (snapshots == null || !snapshots.isArray() || snapshots.isEmpty() || snapshots.size() > MAX_ITEMS) {
            return new Outcome(0, 0, 0, false);
        }
        JsonNode deviceInfo = root.path("device");

        DeviceHealthStore.DeviceProfile profile = new DeviceHealthStore.DeviceProfile(
                pick(deviceInfo, "manufacturer", str(device, "mobile.vendor")),
                pick(deviceInfo, "model", str(device, "mobile.model")),
                pick(deviceInfo, "androidVersion", str(device, "mobile.androidVersion")),
                pick(deviceInfo, "appVersion", str(device, "mobile.appVersion")),
                healthState(device),
                longOrNull(device, "mobile.lastFixTime"),
                pick(deviceInfo, "readiness", str(device, "mobile.readinessVerdict")),
                pick(deviceInfo, "continuity", str(device, "mobile.continuityState")),
                pick(deviceInfo, "continuityCause", str(device, "mobile.continuityCause")),
                pick(deviceInfo, "recovery", str(device, "mobile.recoveryState")));

        int accepted = 0;
        int duplicates = 0;
        int rejected = 0;
        String latestState = null;
        long latestStateTs = Long.MIN_VALUE;
        Integer latestOutbox = null;
        for (JsonNode item : snapshots) {
            DeviceHealthStore.Snapshot snapshot = parse(deviceId, item, nowMs, profile.healthState());
            if (snapshot == null) {
                rejected++;
                continue;
            }
            String itemState = healthStateOf(item, null);
            if (itemState != null && snapshot.tsMs() >= latestStateTs) {
                latestStateTs = snapshot.tsMs();
                latestState = itemState;
                latestOutbox = snapshot.outbox();
            }
            try {
                if (store.insert(snapshot, profile)) {
                    accepted++;
                } else {
                    duplicates++;
                }
            } catch (Exception error) {
                LOGGER.warn("Failed to persist health snapshot for device {}", deviceId, error);
                rejected++;
            }
        }

        if (accepted > 0 || duplicates > 0) {
            lastAcceptedByDevice.put(deviceId, nowMs);
            device.getAttributes().put("mobile.healthAt", nowMs);
            if (latestState != null) {
                device.getAttributes().put("mobile.healthState", latestState);
            }
            if (latestOutbox != null) {
                device.getAttributes().put("mobile.healthOutbox", latestOutbox);
            }
            // Perfil de capacidad (§22): solo datos no sensibles y no vacíos.
            putIfPresent(device, "mobile.androidVersion", pick(deviceInfo, "androidVersion", null));
            putIfPresent(device, "mobile.androidSdk", pick(deviceInfo, "androidSdk", null));
            putIfPresent(device, "mobile.rom", pick(deviceInfo, "rom", null));
            putIfPresent(device, "mobile.standbyBucket", pick(deviceInfo, "standbyBucket", null));
            putIfPresent(device, "mobile.backgroundRestricted", pick(deviceInfo, "backgroundRestricted", null));
            putIfPresent(device, "mobile.batteryOptimized", pick(deviceInfo, "batteryOptimized", null));
            try {
                storage.updateObject(device, new Request(
                        new Columns.Include("attributes"), new Condition.Equals("id", deviceId)));
                cacheManager.invalidateObject(true, Device.class, deviceId, ObjectOperation.UPDATE);
            } catch (Exception error) {
                LOGGER.warn("Failed to update health attributes for device {}", deviceId, error);
            }
        }
        pruneIfDue(nowMs);
        return new Outcome(accepted, duplicates, rejected, false);
    }

    /** Último estado de salud derivado reportado por el cliente (whitelist). */
    private String healthState(Device device) {
        Object value = device.getAttributes().get("mobile.healthState");
        return value instanceof String text && HEALTH_STATES.contains(text) ? text : "UNKNOWN";
    }

    /** Parseo/validación de un snapshot. null = item rechazado (no se inventa). */
    DeviceHealthStore.Snapshot parse(long deviceId, JsonNode item, long nowMs, String fallbackState) {
        if (item == null || !item.isObject()) {
            return null;
        }
        JsonNode reported = item.get("deviceId");
        if (reported != null && reported.canConvertToLong() && reported.asLong() != deviceId) {
            LOGGER.warn("Health deviceId mismatch: authenticated {} vs body {}", deviceId, reported.asLong());
            return null;
        }
        long ts = item.path("wallMs").asLong(0L);
        if (ts <= 0L || ts > nowMs + MAX_FUTURE_SKEW_MS || ts < nowMs - MAX_PAST_AGE_MS) {
            return null;
        }
        String eventType = text(item, "eventType", 24);
        if (eventType == null || !EVENT_TYPES.contains(eventType)) {
            return null;
        }
        String motion = text(item, "motion", 16);
        if (motion != null && !MOTION_VALUES.contains(motion)) {
            motion = null;
        }
        String network = text(item, "network", 16);
        if (network != null && !NETWORK_VALUES.contains(network)) {
            network = null;
        }
        long elapsed = item.path("elapsedMs").asLong(-1L);
        Long wallBucket = item.hasNonNull("wallBucket") ? item.get("wallBucket").asLong() : null;
        int outbox = item.path("outbox").asInt(0);
        if (outbox < 0 || outbox > 10_000_000) {
            outbox = 0;
        }
        return new DeviceHealthStore.Snapshot(
                deviceId, ts,
                text(item, "sessionId", 64),
                wallBucket,
                elapsed >= 0L ? elapsed : null,
                eventType,
                text(item, "reason", 64),
                healthStateOf(item, fallbackState),
                item.path("fgs").asBoolean(false),
                motion, network, outbox,
                funnelOf(item));
    }

    /** F0: embudo del bucket (JSON compacto, tope 512) o null si no vino. */
    static String funnelOf(JsonNode item) {
        JsonNode value = item != null ? item.get("funnel") : null;
        if (value == null || !value.isTextual()) {
            return null;
        }
        String text = value.asText();
        if (text.isBlank() || text.length() > 512 || text.charAt(0) != '{') {
            return null;
        }
        return text;
    }

    /** healthState del snapshot (whitelist); null si el payload no lo trae. */
    static String healthStateOf(JsonNode item, String fallback) {
        JsonNode value = item != null ? item.get("healthState") : null;
        if (value != null && value.isTextual() && HEALTH_STATES.contains(value.asText())) {
            return value.asText();
        }
        return fallback;
    }

    private void pruneIfDue(long nowMs) {
        long last = lastPruneAtMs;
        if (nowMs - last < 3_600_000L) {
            return;
        }
        lastPruneAtMs = nowMs;
        try {
            int pruned = store.pruneOlderThan(nowMs - RETENTION_MS);
            if (pruned > 0) {
                LOGGER.info("Device health retention: pruned {} rows older than 90 days", pruned);
            }
        } catch (Exception error) {
            LOGGER.warn("Device health retention prune failed", error);
        }
    }

    private static void putIfPresent(Device device, String key, String value) {
        if (value != null && !value.isBlank() && value.length() <= 64) {
            device.getAttributes().put(key, value);
        }
    }

    private static String pick(JsonNode node, String field, String fallback) {
        if (node == null || node.isMissingNode()) {
            return fallback;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String text = value.asText().trim();
        if (text.isEmpty() || text.length() > 64) {
            return fallback;
        }
        return text;
    }

    private static String text(JsonNode node, String field, int maxLen) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            return null;
        }
        String text = value.asText().trim();
        if (text.isEmpty() || text.length() > maxLen) {
            return null;
        }
        return text;
    }

    private static String str(Device device, String key) {
        Object value = device.getAttributes().get(key);
        return value != null ? value.toString() : null;
    }

    private static Long longOrNull(Device device, String key) {
        Object value = device.getAttributes().get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }
}
