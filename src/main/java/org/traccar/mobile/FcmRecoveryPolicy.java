package org.traccar.mobile;

import java.util.Set;

/**
 * F2: política PURA (JVM) de FCM Recovery. Decide elegibilidad (feature flag,
 * cooldown, rate limit, intento activo, token) y valida la evidencia
 * end-to-end: RECOVERY_SUCCESS SOLO con todas las etapas confirmadas —
 * nunca por la mera llegada del FCM.
 */
public final class FcmRecoveryPolicy {

    public enum Decision {
        ALLOW,
        SKIP_DISABLED,
        SKIP_COOLDOWN,
        SKIP_RATE_LIMIT,
        SKIP_ACTIVE_ATTEMPT,
        SKIP_NO_TOKEN,
    }

    /** Estados de evidencia, en orden creciente de confirmación. */
    public enum Stage {
        ATTEMPT, RECEIVED, STARTED, FGS_ACTIVE, TRACKING_ACTIVE, GPS_CONFIRMED, SERVER_ACK,
    }

    /** Etapas mínimas para declarar SUCCESS (exigencia end-to-end). */
    public static final Set<Stage> REQUIRED_FOR_SUCCESS = Set.of(
            Stage.RECEIVED, Stage.STARTED, Stage.FGS_ACTIVE,
            Stage.TRACKING_ACTIVE, Stage.GPS_CONFIRMED, Stage.SERVER_ACK);

    private FcmRecoveryPolicy() {
    }

    /** Elegibilidad del probe FCM (silenzio detectado por MobileSilenceMonitor). */
    public static Decision decide(
            boolean featureEnabled,
            boolean hasActiveToken,
            boolean activeAttemptExists,
            long lastAttemptAtMs,
            int attemptsInLastHour,
            long nowMs,
            long cooldownMs,
            int maxPerHour) {
        if (!featureEnabled) {
            return Decision.SKIP_DISABLED;
        }
        if (!hasActiveToken) {
            return Decision.SKIP_NO_TOKEN;
        }
        if (activeAttemptExists) {
            return Decision.SKIP_ACTIVE_ATTEMPT;
        }
        if (lastAttemptAtMs > 0 && nowMs - lastAttemptAtMs < cooldownMs) {
            return Decision.SKIP_COOLDOWN;
        }
        if (attemptsInLastHour >= maxPerHour) {
            return Decision.SKIP_RATE_LIMIT;
        }
        return Decision.ALLOW;
    }

    /** R7: ventana corta de "éxito suave": posición real ≤5 min tras el probe. */
    public static final long SOFT_SUCCESS_WINDOW_MS = 300_000L;

    /** Éxito suave: llegó posición real cerca del probe (métrica honesta aparte). */
    public static boolean isSoftSuccess(long latencyMs) {
        return latencyMs >= 0 && latencyMs <= SOFT_SUCCESS_WINDOW_MS;
    }

    /** ¿La evidencia acumulada basta para SUCCESS? (todas las etapas exigidas). */
    public static boolean isSuccess(Set<Stage> confirmedStages) {
        return confirmedStages.containsAll(REQUIRED_FOR_SUCCESS);
    }

    /** Valida que un ACK del cliente avance en orden y no fabrique éxito. */
    public static boolean isValidTransition(Set<Stage> confirmed, Stage next) {
        if (confirmed.contains(next)) {
            return false; // idempotente: ya confirmado, no re-registrar
        }
        return switch (next) {
            case RECEIVED -> true;
            case STARTED -> confirmed.contains(Stage.RECEIVED);
            case FGS_ACTIVE -> confirmed.contains(Stage.STARTED);
            case TRACKING_ACTIVE -> confirmed.contains(Stage.FGS_ACTIVE);
            case GPS_CONFIRMED -> confirmed.contains(Stage.TRACKING_ACTIVE);
            // SERVER_ACK NUNCA es ack del cliente: sólo el servidor lo fija al
            // recibir una posición real posterior al probe.
            case SERVER_ACK -> false;
            default -> false;
        };
    }

    /** Causa de fallo por timeout según la última etapa confirmada. */
    public static String timeoutReason(Set<Stage> confirmed) {
        if (!confirmed.contains(Stage.RECEIVED)) {
            return "TIMEOUT_NO_DELIVERY";
        }
        if (!confirmed.contains(Stage.FGS_ACTIVE)) {
            return "TIMEOUT_NO_FGS";
        }
        if (!confirmed.contains(Stage.TRACKING_ACTIVE)) {
            return "TIMEOUT_NO_TRACKING";
        }
        if (!confirmed.contains(Stage.GPS_CONFIRMED)) {
            return "TIMEOUT_NO_GPS";
        }
        return "TIMEOUT_NO_SERVER_ACK";
    }

    /** Prioridad FCM observada en Android normalizada para auditoría. */
    public static String priorityLabel(String androidPriority) {
        return switch (androidPriority == null ? "" : androidPriority.toUpperCase()) {
            case "HIGH" -> "HIGH";
            case "NORMAL" -> "NORMAL";
            case "DEGRADED" -> "DEGRADED";
            default -> "UNKNOWN";
        };
    }
}
