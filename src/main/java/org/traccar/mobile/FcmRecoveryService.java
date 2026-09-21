package org.traccar.mobile;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * F2: orquestador de FCM Recovery.
 *
 * Flujo: MobileSilenceMonitor detecta silencio anormal → [onSilenceDetected]
 * decide elegibilidad (feature flag/cooldown/rate-limit/intento activo/token)
 * → crea intento (attemptId) → envía probe HIGH priority → Android confirma
 * etapas (RECEIVED/STARTED/FGS_ACTIVE/TRACKING_ACTIVE/GPS_CONFIRMED) por el
 * endpoint ACK → el servidor fija SERVER_ACK al recibir una posición real
 * posterior → SUCCESS SOLO con toda la evidencia; si no, TIMEOUT con causa.
 *
 * Todo se audita en tc_recovery_event (F0). Nunca se inventa SUCCESS.
 */
@Singleton
public class FcmRecoveryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FcmRecoveryService.class);

    private final Config config;
    private final DataSource dataSource;
    private final FcmSender sender;
    private final FcmTokenStore tokens;

    @Inject
    public FcmRecoveryService(Config config, DataSource dataSource, FcmSender sender, FcmTokenStore tokens) {
        this.config = config;
        this.dataSource = dataSource;
        this.sender = sender;
        this.tokens = tokens;
    }

    public boolean enabled() {
        return config.getBoolean(Keys.FCM_RECOVERY_ENABLED);
    }

    public FcmSender.Status senderStatus() {
        return sender.status();
    }

    /**
     * Punto de entrada desde el watchdog de silencio. Nunca lanza.
     * `uniqueId` es la clave de ruteo que viaja en el payload FCM: la app valida
     * el probe contra su propio username/uniqueId, nunca contra el id numérico
     * interno (F2 P1: BLOCKED_DEVICE_MISMATCH). El id numérico solo se usa para
     * auditoría/DB.
     */
    public void onSilenceDetected(long deviceId, String uniqueId, String silenceReason) {
        try {
            long now = System.currentTimeMillis();
            long cooldownMs = config.getInteger(Keys.FCM_RECOVERY_COOLDOWN_SECONDS) * 1000L;
            int maxPerHour = config.getInteger(Keys.FCM_RECOVERY_MAX_PER_HOUR);
            boolean activeAttempt = hasActiveAttempt(deviceId, now);
            long lastAttempt = lastAttemptAt(deviceId);
            int attemptsLastHour = attemptsSince(deviceId, now - 3_600_000L);
            String token = tokens.activeToken(deviceId);

            FcmRecoveryPolicy.Decision decision = FcmRecoveryPolicy.decide(
                    enabled(), token != null, activeAttempt, lastAttempt,
                    attemptsLastHour, now, cooldownMs, maxPerHour);

            if (decision != FcmRecoveryPolicy.Decision.ALLOW) {
                record(deviceId, null, "RECOVERY_SKIPPED_" + decision.name().replace("SKIP_", ""),
                        silenceReason, "SERVER", null, null);
                LOGGER.info("FCM recovery skipped for device {}: {}", deviceId, decision);
                return;
            }

            String attemptId = "fcm-" + UUID.randomUUID();
            record(deviceId, attemptId, "RECOVERY_ATTEMPT", silenceReason, "FCM", null, "HIGH");

            FcmSender.SendResult result = sender.sendRecoveryProbe(token, attemptId, uniqueId, now);
            if (result.invalidToken()) {
                tokens.invalidate(token);
                record(deviceId, attemptId, "RECOVERY_BLOCKED", "BLOCKED_INVALID_TOKEN",
                        "FCM", null, "HIGH");
                LOGGER.warn("FCM recovery: token inválido para device {} (invalidado)", deviceId);
                return;
            }
            if (result.messageId() == null) {
                record(deviceId, attemptId, "RECOVERY_BLOCKED",
                        "BLOCKED_FCM_SEND_FAILURE".equals(result.errorCode())
                                ? result.errorCode() : "BLOCKED_" + result.errorCode(),
                        "FCM", null, "HIGH");
                LOGGER.warn("FCM recovery send failed for device {}: {}", deviceId, result.errorCode());
                return;
            }
            record(deviceId, attemptId, "RECOVERY_SENT", silenceReason, "FCM", result.messageId(), "HIGH");
            LOGGER.info("FCM recovery probe enviado a device {} (attempt={}, msgId={})",
                    deviceId, attemptId, result.messageId());
        } catch (Exception error) {
            LOGGER.warn("FCM recovery onSilenceDetected failed for device {}", deviceId, error);
        }
    }

    /** ACK desde Android (endpoint). Valida orden/idempotencia; nunca fabrica éxito. */
    public boolean acknowledge(long deviceId, String attemptId, String stage, String priority, String reason) {
        try {
            // Acepta stage con prefijo RECOVERY_ (formato del cliente Android).
            String normalized = stage.startsWith("RECOVERY_") ? stage.substring("RECOVERY_".length()) : stage;
            if ("BLOCKED".equals(normalized) || "FAILED".equals(normalized) || "TIMEOUT".equals(normalized)) {
                record(deviceId, attemptId, "RECOVERY_" + normalized, reason, "ANDROID", null,
                        FcmRecoveryPolicy.priorityLabel(priority));
                return true;
            }
            FcmRecoveryPolicy.Stage target;
            try {
                target = FcmRecoveryPolicy.Stage.valueOf(normalized);
            } catch (IllegalArgumentException unknownStage) {
                record(deviceId, attemptId, "RECOVERY_ACK_REJECTED", "UNKNOWN_STAGE_" + stage,
                        "ANDROID", null, FcmRecoveryPolicy.priorityLabel(priority));
                return false;
            }
            Set<FcmRecoveryPolicy.Stage> confirmed = stages(attemptId);
            if (!FcmRecoveryPolicy.isValidTransition(confirmed, target)) {
                record(deviceId, attemptId, "RECOVERY_ACK_REJECTED",
                        "INVALID_TRANSITION_" + stage, "ANDROID", null,
                        FcmRecoveryPolicy.priorityLabel(priority));
                return false;
            }
            record(deviceId, attemptId, target.name(), reason, "ANDROID", null,
                    FcmRecoveryPolicy.priorityLabel(priority));
            return true;
        } catch (Exception error) {
            LOGGER.warn("FCM recovery ack failed device={} attempt={} stage={}", deviceId, attemptId, stage, error);
            return false;
        }
    }

    /**
     * Evidencia de recuperación real: llegó una posición aceptada del device.
     * Si hay un intento en curso, fija SERVER_ACK y evalúa SUCCESS end-to-end.
     */
    public void onPositionAccepted(long deviceId) {
        try {
            String attemptId = latestOpenAttempt(deviceId);
            if (attemptId == null) {
                return;
            }
            Set<FcmRecoveryPolicy.Stage> confirmed = stages(attemptId);
            // El server no puede saber si el GPS del teléfono se recuperó; sólo
            // que llegó evidencia real al servidor tras el probe. SERVER_ACK es
            // SIEMPRE del servidor: se fija aquí y jamás por ACK del cliente.
            if (!confirmed.contains(FcmRecoveryPolicy.Stage.SERVER_ACK)) {
                record(deviceId, attemptId, "RECOVERY_SERVER_ACK", "position-after-probe", "SERVER", null, null);
            }
            // R7: métrica honesta aparte — éxito "suave" = posición real dentro
            // de los 5 min posteriores al probe, aunque no se hayan confirmado
            // todas las etapas del ACK (permite medir efectividad real sin
            // fabricar el SUCCESS end-to-end, que sigue exigiendo todo).
            long latencyMs = sentLatencyMs(attemptId);
            if (FcmRecoveryPolicy.isSoftSuccess(latencyMs)
                    && !hasEvent(attemptId, "RECOVERY_SOFT_SUCCESS")) {
                record(deviceId, attemptId, "RECOVERY_SOFT_SUCCESS",
                        "position+" + (latencyMs / 1000L) + "s", "SERVER", null, null);
            }
            Set<FcmRecoveryPolicy.Stage> updated = stages(attemptId);
            if (FcmRecoveryPolicy.isSuccess(updated)) {
                record(deviceId, attemptId, "RECOVERY_SUCCESS", "end-to-end", "SERVER", null, null);
                LOGGER.info("RECOVERY_SUCCESS device={} attempt={}", deviceId, attemptId);
            }
        } catch (Exception error) {
            LOGGER.warn("FCM recovery onPositionAccepted failed device={}", deviceId, error);
        }
    }

    /** Timeouts: intentos sin éxito pasado el timeout de evidencia. Idempotente. */
    public void markTimeouts() {
        try {
            long now = System.currentTimeMillis();
            long timeoutMs = config.getInteger(Keys.FCM_RECOVERY_EVIDENCE_TIMEOUT_SECONDS) * 1000L;
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement = connection.prepareStatement(
                            "SELECT DISTINCT attemptid, deviceid FROM tc_recovery_event "
                                    + "WHERE eventtype = 'RECOVERY_SENT' AND ts < ?")) {
                statement.setTimestamp(1, new Timestamp(now - timeoutMs));
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        String attemptId = rs.getString(1);
                        long deviceId = rs.getLong(2);
                        Set<FcmRecoveryPolicy.Stage> confirmed = stages(attemptId);
                        // Terminal una sola vez: SUCCESS o TIMEOUT. Un intento con
                        // SERVER_ACK pero sin todas las etapas tampoco es éxito y
                        // debe terminar con causa (p.ej. TIMEOUT_NO_GPS).
                        if (!hasEvent(attemptId, "RECOVERY_TIMEOUT") && !hasEvent(attemptId, "RECOVERY_SUCCESS")) {
                            record(deviceId, attemptId, "RECOVERY_TIMEOUT",
                                    FcmRecoveryPolicy.timeoutReason(confirmed), "SERVER", null, null);
                        }
                    }
                }
            }
        } catch (Exception error) {
            LOGGER.warn("FCM recovery markTimeouts failed", error);
        }
    }

    // ------------------------------------------------------------------
    // Persistencia (tc_recovery_event, F0)
    // ------------------------------------------------------------------

    private void record(long deviceId, String attemptId, String eventType, String reason,
            String source, String fcmMessageId, String priority) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO tc_recovery_event (deviceid, ts, eventtype, reason, attemptid, status, "
                                + "fcmmsgid, fcmpriority, source) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, deviceId);
            statement.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
            // Truncado defensivo a los límites del schema: ningún valor
            // (incluido el attemptId del cliente) puede romper la auditoría.
            statement.setString(3, truncate(eventType, 32));
            statement.setString(4, truncate(reason, 32));
            statement.setString(5, truncate(attemptId, 64));
            statement.setString(6, eventType.contains("SUCCESS") ? "OK"
                    : eventType.contains("BLOCKED") || eventType.contains("FAILED")
                            || eventType.contains("TIMEOUT") ? "FAILED" : "PENDING");
            statement.setString(7, truncate(fcmMessageId, 128));
            statement.setString(8, truncate(priority, 16));
            statement.setString(9, truncate(source, 16));
            statement.executeUpdate();
        } catch (Exception error) {
            LOGGER.warn("FCM recovery event persist failed type={}: {}", eventType, error.getMessage());
        }
    }

    /** Latencia desde RECOVERY_SENT; -1 si no se puede medir (sin BD). */
    private long sentLatencyMs(String attemptId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT MIN(ts) FROM tc_recovery_event WHERE attemptid = ? "
                                + "AND eventtype = 'RECOVERY_SENT'")) {
            statement.setString(1, attemptId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next() && rs.getTimestamp(1) != null) {
                    return System.currentTimeMillis() - rs.getTimestamp(1).getTime();
                }
            }
        } catch (Exception ignored) {
            // sin BD no se mide: nunca se fabrica una métrica
        }
        return -1L;
    }

    private Set<FcmRecoveryPolicy.Stage> stages(String attemptId) {
        Set<FcmRecoveryPolicy.Stage> result = new HashSet<>();
        result.add(FcmRecoveryPolicy.Stage.ATTEMPT);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT DISTINCT eventtype FROM tc_recovery_event WHERE attemptid = ?")) {
            statement.setString(1, attemptId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    FcmRecoveryPolicy.Stage stage = parseStage(rs.getString(1));
                    if (stage != null) {
                        result.add(stage);
                    }
                    // eventos no-etapa (SKIPPED/TIMEOUT/SUCCESS) no cuentan como evidencia
                }
            }
        } catch (Exception ignored) {
            // sin BD no hay evidencia: nunca se fabrica SUCCESS
        }
        return result;
    }

    /** Acepta tanto "SERVER_ACK" como "RECOVERY_SERVER_ACK" (evidencia F0). */
    private static FcmRecoveryPolicy.Stage parseStage(String eventType) {
        if (eventType == null) {
            return null;
        }
        String normalized = eventType.startsWith("RECOVERY_")
                ? eventType.substring("RECOVERY_".length()) : eventType;
        try {
            return FcmRecoveryPolicy.Stage.valueOf(normalized);
        } catch (IllegalArgumentException notAStage) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        return value == null ? null : value.substring(0, Math.min(max, value.length()));
    }

    private boolean hasEvent(String attemptId, String eventType) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM tc_recovery_event WHERE attemptid = ? AND eventtype = ? LIMIT 1")) {
            statement.setString(1, attemptId);
            statement.setString(2, eventType);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (Exception error) {
            return false;
        }
    }

    private boolean hasActiveAttempt(long deviceId, long nowMs) {
        long timeoutMs = config.getInteger(Keys.FCM_RECOVERY_EVIDENCE_TIMEOUT_SECONDS) * 1000L;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM tc_recovery_event e WHERE e.deviceid = ? AND e.eventtype = 'RECOVERY_SENT' "
                                + "AND e.ts > ? AND NOT EXISTS (SELECT 1 FROM tc_recovery_event f "
                                + "WHERE f.attemptid = e.attemptid AND f.eventtype IN "
                                + "('RECOVERY_SUCCESS','RECOVERY_TIMEOUT','RECOVERY_BLOCKED')) LIMIT 1")) {
            statement.setLong(1, deviceId);
            statement.setTimestamp(2, new Timestamp(nowMs - timeoutMs));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (Exception error) {
            return true; // conservador: sin BD no se envía otro probe
        }
    }

    private long lastAttemptAt(long deviceId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT MAX(ts) FROM tc_recovery_event WHERE deviceid = ? "
                                + "AND eventtype IN ('RECOVERY_ATTEMPT','RECOVERY_SENT')")) {
            statement.setLong(1, deviceId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next() && rs.getTimestamp(1) != null) {
                    return rs.getTimestamp(1).getTime();
                }
            }
        } catch (Exception error) {
            return System.currentTimeMillis(); // conservador
        }
        return 0L;
    }

    private int attemptsSince(long deviceId, long sinceMs) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM tc_recovery_event WHERE deviceid = ? "
                                + "AND eventtype = 'RECOVERY_SENT' AND ts >= ?")) {
            statement.setLong(1, deviceId);
            statement.setTimestamp(2, new Timestamp(sinceMs));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception error) {
            return Integer.MAX_VALUE; // conservador
        }
    }

    private String latestOpenAttempt(long deviceId) {
        long timeoutMs = config.getInteger(Keys.FCM_RECOVERY_EVIDENCE_TIMEOUT_SECONDS) * 1000L;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT e.attemptid FROM tc_recovery_event e WHERE e.deviceid = ? "
                                + "AND e.eventtype = 'RECOVERY_SENT' AND e.ts > ? "
                                + "AND NOT EXISTS (SELECT 1 FROM tc_recovery_event f "
                                + "WHERE f.attemptid = e.attemptid AND f.eventtype IN "
                                + "('RECOVERY_SUCCESS','RECOVERY_TIMEOUT')) "
                                + "ORDER BY e.ts DESC LIMIT 1")) {
            statement.setLong(1, deviceId);
            statement.setTimestamp(2, new Timestamp(System.currentTimeMillis() - timeoutMs));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception error) {
            return null;
        }
    }
}
