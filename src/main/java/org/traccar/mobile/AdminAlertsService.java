package org.traccar.mobile;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Device;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Request;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Alertas operativas del panel web (GET /api/admin/alerts): reúne en una sola
 * vista lo que un supervisor debe atender — jornadas activas sin reportar,
 * eventos críticos de {@code tc_device_health}, intentos FCM no exitosos de
 * {@code tc_recovery_event} y el estado del sistema (respaldo, disco, arranque
 * del server y log del watchdog).
 *
 * <p>Solo lectura: SELECT sobre la BD y lecturas best-effort de /var. Ninguna
 * fuente ausente puede romper la respuesta: cada bloque degrada a vacío o a
 * desconocido y lo registra en el log. La clasificación de severidad y los
 * umbrales son funciones puras estáticas para poder testearlas sin DI ni
 * filesystem real.</p>
 */
@Singleton
public class AdminAlertsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminAlertsService.class);

    public static final String SEVERITY_INFO = "info";
    public static final String SEVERITY_WARNING = "warning";
    public static final String SEVERITY_CRITICAL = "critical";

    public static final String CATEGORY_JOURNEY_SILENCE = "journey-silence";
    public static final String CATEGORY_DEVICE_HEALTH = "device-health";
    public static final String CATEGORY_RECOVERY = "recovery";
    public static final String CATEGORY_HEALTH_FUNNEL = "health-funnel";
    public static final String CATEGORY_BACKUP = "backup";
    public static final String CATEGORY_DISK = "disk";
    public static final String CATEGORY_WATCHDOG = "watchdog";

    /** Jornada activa sin reportar: warning a los 10 min, critical a los 30. */
    public static final long SILENCE_THRESHOLD_MS = 10L * 60 * 1000;
    public static final long SILENCE_CRITICAL_MS = 30L * 60 * 1000;
    /** Ventana de eventos recientes (24 h). */
    public static final long WINDOW_MS = 24L * 60 * 60 * 1000;
    /** Embudo F0: warning a las 24 h sin buckets con jornada activa y equipo vivo, critical a las 48 h. */
    public static final long FUNNEL_WARNING_MS = 24L * 60 * 60 * 1000;
    public static final long FUNNEL_CRITICAL_MS = 48L * 60 * 60 * 1000;
    /** Primera versión de app con embudo (F0): por debajo no se puede alertar "nunca envió". */
    public static final String FUNNEL_MIN_APP_VERSION = "1.1.22";
    /** Cliente nativo DMujeres: el único que envía embudo de salud. */
    public static final String NATIVE_CLIENT = "dmujeres-native";
    public static final String ATTR_MOBILE_CLIENT = "mobile.client";
    /** Respaldo: warning a las 24 h sin dump nuevo, critical a las 36 h. */
    public static final long BACKUP_WARNING_HOURS = 24L;
    public static final long BACKUP_CRITICAL_HOURS = 36L;
    /** Disco: warning al 75% de uso, critical al 90%. */
    public static final int DISK_WARNING_PERCENT = 75;
    public static final int DISK_CRITICAL_PERCENT = 90;

    public static final String BACKUP_DIR = "/var/backups/dmj";
    public static final String WATCHDOG_LOG = "/var/log/dmj/watchdog.log";

    static final int MAX_ALERTS_PER_SOURCE = 200;
    static final int WATCHDOG_TAIL_LINES = 5;
    static final int TAIL_WINDOW_BYTES = 32 * 1024;

    private static final String HEALTH_SQL = """
            SELECT h.deviceid, h.ts, h.eventtype, h.reason, d.name
            FROM tc_device_health h
            LEFT JOIN tc_devices d ON d.id = h.deviceid
            WHERE h.ts >= ? AND h.eventtype IN ('PROCESS_FREEZE_DETECTED', 'CRITICAL')
            ORDER BY h.ts DESC
            LIMIT ?
            """;

    private static final String FUNNEL_SQL = """
            SELECT d.id AS deviceid, d.name AS name, MAX(h.ts) AS lastbucket
            FROM tc_devices d
            LEFT JOIN tc_device_health h ON h.deviceid = d.id AND h.attributes ? 'funnel'
            WHERE d.id = ANY (?)
            GROUP BY d.id, d.name
            """;

    private static final String RECOVERY_SQL = """
            SELECT r.deviceid, r.ts, r.eventtype, r.reason, d.name
            FROM tc_recovery_event r
            LEFT JOIN tc_devices d ON d.id = r.deviceid
            WHERE r.ts >= ? AND r.eventtype IN ('RECOVERY_TIMEOUT', 'RECOVERY_BLOCKED')
            ORDER BY r.ts DESC
            LIMIT ?
            """;

    private static final Comparator<Alert> ALERT_ORDER = (first, second) -> {
        int bySeverity = Integer.compare(severityRank(second.severity()), severityRank(first.severity()));
        return bySeverity != 0 ? bySeverity : Long.compare(second.ts(), first.ts());
    };

    private final DataSource dataSource;
    private final Storage storage;
    private final MobileJourneyRegistry journeyRegistry;
    private final Path backupDir;
    private final Path watchdogLog;

    @Inject
    public AdminAlertsService(DataSource dataSource, Storage storage, MobileJourneyRegistry journeyRegistry) {
        this(dataSource, storage, journeyRegistry, Path.of(BACKUP_DIR), Path.of(WATCHDOG_LOG));
    }

    AdminAlertsService(DataSource dataSource, Storage storage, MobileJourneyRegistry journeyRegistry,
            Path backupDir, Path watchdogLog) {
        this.dataSource = dataSource;
        this.storage = storage;
        this.journeyRegistry = journeyRegistry;
        this.backupDir = backupDir;
        this.watchdogLog = watchdogLog;
    }

    /** Alerta individual. deviceId/deviceName son null en alertas de sistema. */
    public record Alert(String severity, String category, Long deviceId, String deviceName,
            String message, long ts) {}

    /** Último respaldo hallado en el directorio de respaldos. */
    public record BackupStatus(String path, boolean available, Long modifiedAt, Long sizeBytes,
            Long ageHours) {}

    /** Uso de la partición donde vive el respaldo. */
    public record DiskStatus(String path, boolean available, Long totalBytes, Long usableBytes,
            Integer usedPercent) {}

    /** Últimas líneas del log del watchdog ({@code lines} es null si no existe). */
    public record WatchdogStatus(String path, boolean available, Long modifiedAt, List<String> lines) {}

    /** Contadores de ingesta rechazada (R8.2 H4): visibilidad, no silencio. */
    public record IngestRejectionStatus(long total, Map<String, Long> byReason) {}

    /** Estado del sistema que alimenta las tarjetas del panel. */
    public record SystemStatus(long serverStartedAt, long uptimeSeconds, BackupStatus backup,
            DiskStatus disk, WatchdogStatus watchdog, IngestRejectionStatus ingestRejections) {}

    /** Respuesta completa del endpoint. */
    public record AlertsReport(long generatedAt, SystemStatus system, List<Alert> alerts, int total) {}

    /** Reúne todas las fuentes. Nunca lanza. */
    public AlertsReport collect() {
        long now = System.currentTimeMillis();
        List<Alert> alerts = new ArrayList<>();
        alerts.addAll(silenceAlerts(now));
        alerts.addAll(healthAlerts(now));
        alerts.addAll(recoveryAlerts(now));
        alerts.addAll(funnelAlerts(now));
        SystemStatus system = systemStatus(now);
        alerts.addAll(systemAlerts(system, now));
        alerts.sort(ALERT_ORDER);
        return new AlertsReport(now, system, alerts, alerts.size());
    }

    // ------------------------------------------------------------------
    // Jornadas activas sin reportar
    // ------------------------------------------------------------------

    List<Alert> silenceAlerts(long now) {
        List<Alert> alerts = new ArrayList<>();
        Set<Long> active = new HashSet<>(journeyRegistry.activeDeviceIds());
        if (active.isEmpty()) {
            return alerts;
        }
        try {
            List<Device> devices = storage.getObjects(Device.class, new Request(
                    new Columns.Include("id", "name", "uniqueId", "lastUpdate", "attributes")));
            for (Device device : devices) {
                if (!active.contains(device.getId())) {
                    continue;
                }
                Alert alert = silenceAlert(deviceLabel(device), device.getId(), lastSeenAt(device), now);
                if (alert != null) {
                    alerts.add(alert);
                }
            }
        } catch (Exception error) {
            LOGGER.warn("Admin alerts: failed to load devices for silence check", error);
        }
        return alerts;
    }

    /** Última llegada conocida: {@code mobile.lastPresenceAt} o {@code lastUpdate}. */
    static long lastSeenAt(Device device) {
        long presenceAt = 0L;
        Object raw = device.getAttributes().get(MobilePresenceTracker.ATTR_LAST_PRESENCE_AT);
        if (raw != null) {
            try {
                presenceAt = Long.parseLong(raw.toString());
            } catch (NumberFormatException ignored) {
                presenceAt = 0L;
            }
        }
        long lastUpdate = device.getLastUpdate() != null ? device.getLastUpdate().getTime() : 0L;
        return Math.max(presenceAt, lastUpdate);
    }

    static Alert silenceAlert(String deviceName, long deviceId, long lastSeenAt, long now) {
        if (lastSeenAt <= 0L) {
            return null;
        }
        long ageMs = now - lastSeenAt;
        if (ageMs < SILENCE_THRESHOLD_MS) {
            return null;
        }
        String severity = ageMs >= SILENCE_CRITICAL_MS ? SEVERITY_CRITICAL : SEVERITY_WARNING;
        return new Alert(severity, CATEGORY_JOURNEY_SILENCE, deviceId, deviceName,
                "Jornada activa sin reportar desde hace " + ageMs / 60_000L + " min", lastSeenAt);
    }

    // ------------------------------------------------------------------
    // Eventos críticos de tc_device_health (24 h)
    // ------------------------------------------------------------------

    List<Alert> healthAlerts(long now) {
        List<Alert> alerts = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(HEALTH_SQL)) {
            statement.setTimestamp(1, new Timestamp(now - WINDOW_MS));
            statement.setInt(2, MAX_ALERTS_PER_SOURCE);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Alert alert = healthAlert(result.getLong("deviceid"), result.getString("name"),
                            result.getTimestamp("ts").getTime(), result.getString("eventtype"),
                            result.getString("reason"));
                    if (alert != null) {
                        alerts.add(alert);
                    }
                }
            }
        } catch (Exception error) {
            LOGGER.warn("Admin alerts: health query failed: {}", error.getMessage());
        }
        return alerts;
    }

    static String classifyHealthEvent(String eventType) {
        if ("PROCESS_FREEZE_DETECTED".equals(eventType) || "CRITICAL".equals(eventType)) {
            return SEVERITY_CRITICAL;
        }
        return null;
    }

    static Alert healthAlert(long deviceId, String deviceName, long ts, String eventType, String reason) {
        String severity = classifyHealthEvent(eventType);
        if (severity == null) {
            return null;
        }
        String message = "PROCESS_FREEZE_DETECTED".equals(eventType)
                ? "Congelamiento del proceso detectado"
                : "Estado crítico reportado por la app";
        return new Alert(severity, CATEGORY_DEVICE_HEALTH, deviceId, label(deviceName, deviceId),
                withReason(message, reason), ts);
    }

    // ------------------------------------------------------------------
    // F0: embudo de salud (pilotos) — jornada activa, equipo vivo, sin buckets
    // ------------------------------------------------------------------

    /**
     * Dispositivos con jornada activa que SÍ reportan presencia (equipo vivo)
     * pero no envían ningún bucket de embudo en 24 h. Si el equipo está mudo
     * del todo, la alerta de jornada sin reportar ya lo cubre (sin duplicar).
     */
    List<Alert> funnelAlerts(long now) {
        List<Alert> alerts = new ArrayList<>();
        Set<Long> active = new HashSet<>(journeyRegistry.activeDeviceIds());
        if (active.isEmpty()) {
            return alerts;
        }
        try {
            List<Device> devices = storage.getObjects(Device.class, new Request(
                    new Columns.Include("id", "name", "uniqueId", "lastUpdate", "attributes")));
            List<Device> alive = new ArrayList<>();
            for (Device device : devices) {
                // La app de respaldo (OsmAnd + endpoint de jornada) no envía embudo:
                // solo se alerta a clientes nativos (o devices sin mobile.client).
                if (active.contains(device.getId()) && now - lastSeenAt(device) < WINDOW_MS
                        && isNativeClient(device.getAttributes())) {
                    alive.add(device);
                }
            }
            if (alive.isEmpty()) {
                return alerts;
            }
            Long[] ids = alive.stream().map(Device::getId).toArray(Long[]::new);
            Map<Long, Timestamp> lastBucket = new java.util.HashMap<>();
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(FUNNEL_SQL)) {
                statement.setArray(1, connection.createArrayOf("bigint", ids));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        lastBucket.put(result.getLong("deviceid"), result.getTimestamp("lastbucket"));
                    }
                }
            }
            for (Device device : alive) {
                Timestamp ts = lastBucket.get(device.getId());
                Object version = device.getAttributes().get("mobile.appVersion");
                Alert alert = funnelAlert(deviceLabel(device), device.getId(),
                        ts != null ? ts.getTime() : null, version != null ? version.toString() : null, now);
                if (alert != null) {
                    alerts.add(alert);
                }
            }
        } catch (Exception error) {
            LOGGER.warn("Admin alerts: funnel query failed: {}", error.getMessage());
        }
        return alerts;
    }

    /**
     * Función pura: null si hay buckets recientes. Regresión (envió y dejó de
     * enviar) se alerta a las 24 h / 48 h. "Nunca envió" solo se alerta si la
     * versión ya incluye F0 ([FUNNEL_MIN_APP_VERSION]); antes de esa versión el
     * silencio es esperado y alertarlo sería ruido.
     */
    static Alert funnelAlert(String deviceName, long deviceId, Long lastBucketAt, String appVersion, long now) {
        if (lastBucketAt == null) {
            if (!supportsFunnel(appVersion)) {
                return null;
            }
            return new Alert(SEVERITY_WARNING, CATEGORY_HEALTH_FUNNEL, deviceId, label(deviceName, deviceId),
                    "Jornada activa sin telemetría de salud (nunca envió embudo)", now);
        }
        long ageMs = now - lastBucketAt;
        if (ageMs < FUNNEL_WARNING_MS) {
            return null;
        }
        String severity = ageMs >= FUNNEL_CRITICAL_MS ? SEVERITY_CRITICAL : SEVERITY_WARNING;
        return new Alert(severity, CATEGORY_HEALTH_FUNNEL, deviceId, label(deviceName, deviceId),
                "Jornada activa sin telemetría de salud desde hace " + ageMs / (60L * 60 * 1000) + " h",
                lastBucketAt);
    }

    /**
     * Cliente nativo DMujeres: {@code mobile.client} null/blank (equipos viejos,
     * sin atributo) o exactamente "dmujeres-native". Cualquier otro valor
     * (p. ej. "dmujeres-traccar", la app de respaldo) no envía embudo F0 y no
     * debe generar la alerta "nunca envió".
     */
    static boolean isNativeClient(Map<String, Object> attributes) {
        Object value = attributes != null ? attributes.get(ATTR_MOBILE_CLIENT) : null;
        if (value == null) {
            return true;
        }
        String client = value.toString().strip();
        return client.isEmpty() || NATIVE_CLIENT.equals(client);
    }

    /** Compara "1.1.22" o superior contra [FUNNEL_MIN_APP_VERSION]; ilegible = false (no alerta). */
    static boolean supportsFunnel(String appVersion) {
        if (appVersion == null || appVersion.isBlank()) {
            return false;
        }
        String[] actual = appVersion.trim().split("\\.");
        String[] minimum = FUNNEL_MIN_APP_VERSION.split("\\.");
        for (int index = 0; index < minimum.length; index++) {
            int a = index < actual.length ? parseIntSafe(actual[index]) : 0;
            int b = parseIntSafe(minimum[index]);
            if (a != b) {
                return a > b;
            }
        }
        return true;
    }

    private static int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // Intentos FCM no exitosos de tc_recovery_event (24 h)
    // ------------------------------------------------------------------

    List<Alert> recoveryAlerts(long now) {
        List<Alert> alerts = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(RECOVERY_SQL)) {
            statement.setTimestamp(1, new Timestamp(now - WINDOW_MS));
            statement.setInt(2, MAX_ALERTS_PER_SOURCE);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Alert alert = recoveryAlert(result.getLong("deviceid"), result.getString("name"),
                            result.getTimestamp("ts").getTime(), result.getString("eventtype"),
                            result.getString("reason"));
                    if (alert != null) {
                        alerts.add(alert);
                    }
                }
            }
        } catch (Exception error) {
            LOGGER.warn("Admin alerts: recovery query failed: {}", error.getMessage());
        }
        return alerts;
    }

    static String classifyRecoveryEvent(String eventType) {
        if ("RECOVERY_BLOCKED".equals(eventType)) {
            return SEVERITY_CRITICAL;
        }
        if ("RECOVERY_TIMEOUT".equals(eventType)) {
            return SEVERITY_WARNING;
        }
        return null;
    }

    static Alert recoveryAlert(long deviceId, String deviceName, long ts, String eventType, String reason) {
        String severity = classifyRecoveryEvent(eventType);
        if (severity == null) {
            return null;
        }
        String message = "RECOVERY_TIMEOUT".equals(eventType)
                ? "Recuperación FCM sin evidencia (timeout)"
                : "Intento de recuperación FCM bloqueado";
        return new Alert(severity, CATEGORY_RECOVERY, deviceId, label(deviceName, deviceId),
                withReason(message, reason), ts);
    }

    // ------------------------------------------------------------------
    // Estado del sistema (solo lectura, best-effort)
    // ------------------------------------------------------------------

    SystemStatus systemStatus(long now) {
        long startedAt = ManagementFactory.getRuntimeMXBean().getStartTime();
        return new SystemStatus(startedAt, Math.max(0L, (now - startedAt) / 1000L),
                backupStatus(backupDir, now), diskStatus(backupDir), watchdogStatus(watchdogLog),
                ingestRejectionStatus());
    }

    /** R8.2 (H4): rechazos de ingesta desde el arranque del server. */
    static IngestRejectionStatus ingestRejectionStatus() {
        try {
            Map<String, Long> snapshot = MobileRejectionCounter.snapshot();
            long total = snapshot.getOrDefault("total", 0L);
            return new IngestRejectionStatus(total, snapshot);
        } catch (Exception error) {
            return new IngestRejectionStatus(0L, Map.of());
        }
    }

    static BackupStatus backupStatus(Path directory, long now) {
        String path = directory != null ? directory.toString() : null;
        if (directory == null || !Files.isDirectory(directory)) {
            return new BackupStatus(path, false, null, null, null);
        }
        try {
            Path latest = null;
            long latestMs = 0L;
            try (var files = Files.list(directory)) {
                for (Path candidate : (Iterable<Path>) files::iterator) {
                    if (!Files.isRegularFile(candidate)) {
                        continue;
                    }
                    long modified = Files.getLastModifiedTime(candidate).toMillis();
                    if (latest == null || modified > latestMs) {
                        latest = candidate;
                        latestMs = modified;
                    }
                }
            }
            if (latest == null) {
                return new BackupStatus(path, false, null, null, null);
            }
            return new BackupStatus(latest.toString(), true, latestMs, Files.size(latest),
                    (now - latestMs) / 3_600_000L);
        } catch (Exception error) {
            LOGGER.warn("Admin alerts: backup scan failed: {}", error.getMessage());
            return new BackupStatus(path, false, null, null, null);
        }
    }

    static DiskStatus diskStatus(Path path) {
        try {
            File target = path != null && Files.exists(path) ? path.toFile() : new File("/");
            long total = target.getTotalSpace();
            long usable = target.getUsableSpace();
            if (total <= 0L) {
                return new DiskStatus(target.getAbsolutePath(), false, null, null, null);
            }
            int usedPercent = (int) Math.round((total - usable) * 100.0 / total);
            return new DiskStatus(target.getAbsolutePath(), true, total, usable, usedPercent);
        } catch (Exception error) {
            return new DiskStatus(path != null ? path.toString() : null, false, null, null, null);
        }
    }

    static WatchdogStatus watchdogStatus(Path log) {
        String path = log != null ? log.toString() : null;
        if (log == null || !Files.isRegularFile(log)) {
            return new WatchdogStatus(path, false, null, null);
        }
        try {
            return new WatchdogStatus(path, true, Files.getLastModifiedTime(log).toMillis(),
                    readLastLines(log, WATCHDOG_TAIL_LINES));
        } catch (Exception error) {
            LOGGER.warn("Admin alerts: watchdog log read failed: {}", error.getMessage());
            return new WatchdogStatus(path, false, null, null);
        }
    }

    /** Últimas líneas no vacías, leyendo como mucho los últimos 32 KB del archivo. */
    static List<String> readLastLines(Path path, int maxLines) throws IOException {
        byte[] window;
        boolean truncated;
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size == 0L) {
                return List.of();
            }
            int length = (int) Math.min(size, TAIL_WINDOW_BYTES);
            ByteBuffer buffer = ByteBuffer.allocate(length);
            channel.position(size - length);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    break;
                }
            }
            window = buffer.array();
            truncated = size > length;
        }
        List<String> lines = new ArrayList<>();
        for (String line : new String(window, StandardCharsets.UTF_8).split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        if (truncated && !lines.isEmpty()) {
            // La primera línea puede estar cortada por el inicio de la ventana.
            lines.remove(0);
        }
        if (lines.size() > maxLines) {
            lines = new ArrayList<>(lines.subList(lines.size() - maxLines, lines.size()));
        }
        return List.copyOf(lines);
    }

    static List<Alert> systemAlerts(SystemStatus system, long now) {
        List<Alert> alerts = new ArrayList<>();
        if (system == null) {
            return alerts;
        }
        if (system.backup() != null) {
            Alert alert = backupAlert(system.backup(), now);
            if (alert != null) {
                alerts.add(alert);
            }
        }
        if (system.disk() != null) {
            Alert alert = diskAlert(system.disk(), now);
            if (alert != null) {
                alerts.add(alert);
            }
        }
        if (system.watchdog() != null) {
            Alert alert = watchdogAlert(system.watchdog(), now);
            if (alert != null) {
                alerts.add(alert);
            }
        }
        return alerts;
    }

    static Alert backupAlert(BackupStatus backup, long now) {
        if (!backup.available()) {
            return new Alert(SEVERITY_CRITICAL, CATEGORY_BACKUP, null, null,
                    "No se encontró ningún respaldo en " + backup.path(), now);
        }
        Long ageHours = backup.ageHours();
        if (ageHours == null) {
            return null;
        }
        long ts = backup.modifiedAt() != null ? backup.modifiedAt() : now;
        if (ageHours >= BACKUP_CRITICAL_HOURS) {
            return new Alert(SEVERITY_CRITICAL, CATEGORY_BACKUP, null, null,
                    "El respaldo más reciente tiene " + ageHours + " h", ts);
        }
        if (ageHours >= BACKUP_WARNING_HOURS) {
            return new Alert(SEVERITY_WARNING, CATEGORY_BACKUP, null, null,
                    "El respaldo más reciente tiene " + ageHours + " h", ts);
        }
        return null;
    }

    static Alert diskAlert(DiskStatus disk, long now) {
        if (!disk.available() || disk.usedPercent() == null) {
            return null;
        }
        int used = disk.usedPercent();
        if (used >= DISK_CRITICAL_PERCENT) {
            return new Alert(SEVERITY_CRITICAL, CATEGORY_DISK, null, null,
                    "Disco del servidor al " + used + "% de uso", now);
        }
        if (used >= DISK_WARNING_PERCENT) {
            return new Alert(SEVERITY_WARNING, CATEGORY_DISK, null, null,
                    "Disco del servidor al " + used + "% de uso", now);
        }
        return null;
    }

    static Alert watchdogAlert(WatchdogStatus watchdog, long now) {
        if (!watchdog.available() || watchdog.lines() == null || watchdog.lines().isEmpty()) {
            return null;
        }
        String last = watchdog.lines().get(watchdog.lines().size() - 1);
        if (!last.contains("reinicio")) {
            return null;
        }
        long ts = watchdog.modifiedAt() != null ? watchdog.modifiedAt() : now;
        return new Alert(SEVERITY_WARNING, CATEGORY_WATCHDOG, null, null,
                "El watchdog reinició el servidor en el último ciclo", ts);
    }

    // ------------------------------------------------------------------
    // Utilidades puras
    // ------------------------------------------------------------------

    static int severityRank(String severity) {
        if (SEVERITY_CRITICAL.equals(severity)) {
            return 3;
        }
        if (SEVERITY_WARNING.equals(severity)) {
            return 2;
        }
        if (SEVERITY_INFO.equals(severity)) {
            return 1;
        }
        return 0;
    }

    static String withReason(String message, String reason) {
        return reason == null || reason.isBlank() ? message : message + " (motivo: " + reason.strip() + ")";
    }

    static String deviceLabel(Device device) {
        String name = device.getName();
        if (name != null && !name.isBlank()) {
            return name.strip();
        }
        if (device.getUniqueId() != null && !device.getUniqueId().isBlank()) {
            return device.getUniqueId();
        }
        return "Equipo #" + device.getId();
    }

    private static String label(String name, long deviceId) {
        return name != null && !name.isBlank() ? name.strip() : "Equipo #" + deviceId;
    }
}
