package org.traccar.mobile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * R8: política PURA de despliegue OTA gradual (rolling rollout). Decide si un
 * equipo puede ver una versión publicada; la entrega real la sigue gobernando
 * la app (OtaVersionPolicy: versionCode es la autoridad).
 *
 * Reglas, en orden:
 * 1. {@code installedCode < minVersionCode} → {@link Decision#FORCED}: el
 *    kill-switch/versión mínima manda sobre pausa y porcentaje (siempre
 *    recibe el manifiesto; la app decide si es obligatoria).
 * 2. {@code paused} → {@link Decision#DENIED} (salvo FORCED).
 * 3. {@code bucket(uniqueId) < rolloutPercent} → {@link Decision#ALLOWED},
 *    si no {@link Decision#DENIED}.
 *
 * Hash ESTABLE y documentado: {@code SHA-256(uniqueId UTF-8)}, se toman los dos
 * primeros bytes en big-endian y se reduce módulo 100 (bucket 0..99). El mismo
 * equipo obtiene siempre la misma decisión para un porcentaje dado; el operador
 * solo mueve el umbral, nunca re-baraja la asignación.
 */
public final class OtaRolloutPolicy {

    public enum Decision { FORCED, ALLOWED, DENIED }

    /** Número de buckets del rollout (porcentaje 0..100). */
    public static final int BUCKETS = 100;

    private OtaRolloutPolicy() {
    }

    /**
     * @param uniqueId       identidad estable del equipo (username/uniqueId)
     * @param installedCode  versionCode instalado en el equipo
     * @param latestCode     versionCode publicado (informativo: la comparación
     *                       de versiones vive en la app, aquí no bloquea)
     * @param minVersionCode versión mínima soportada (0 = sin mínimo)
     * @param rolloutPercent porcentaje 0..100 (se recorta si viene fuera)
     * @param paused         true detiene el despliegue salvo FORCED
     */
    public static Decision decide(
            String uniqueId,
            long installedCode,
            long latestCode,
            long minVersionCode,
            int rolloutPercent,
            boolean paused,
            java.util.List<String> allowList) {
        // R8.2: MODO PILOTO — si la allowlist NO está vacía, es decisiva:
        // solo los equipos listados reciben el manifiesto (p.ej. solo macias
        // de prueba); el resto queda DENIED hasta que el operador la vacíe
        // (o la amplíe) — control total sin republicar.
        if (allowList != null && !allowList.isEmpty()) {
            return uniqueId != null && allowList.contains(uniqueId)
                    ? Decision.ALLOWED : Decision.DENIED;
        }

        if (installedCode < minVersionCode) {
            return Decision.FORCED;
        }
        if (paused) {
            return Decision.DENIED;
        }
        int percent = Math.max(0, Math.min(BUCKETS, rolloutPercent));
        return bucket(uniqueId) < percent ? Decision.ALLOWED : Decision.DENIED;
    }

    /**
     * Bucket estable 0..99: SHA-256 del uniqueId, primeros 2 bytes big-endian
     * módulo 100. Nunca lanza: un uniqueId nulo/vacío usa cadena vacía.
     */
    public static int bucket(String uniqueId) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 no disponible", error);
        }
        byte[] hash = digest.digest((uniqueId == null ? "" : uniqueId).getBytes(StandardCharsets.UTF_8));
        int value = ((hash[0] & 0xFF) << 8) | (hash[1] & 0xFF);
        return value % BUCKETS;
    }
}
