package org.traccar.mobile;

/**
 * F2: almacén de tokens FCM por dispositivo. Interfaz para permitir tests
 * con implementaciones en memoria; la implementación real es JDBC.
 */
public interface FcmTokenStore {

    record TokenInfo(boolean hasToken, boolean invalid, long updatedAtMs, String tokenHashPrefix) {}

    /** Registra/rota el token del dispositivo (idempotente, token anterior conservado). */
    void register(long deviceId, String token);

    /** Token activo del dispositivo o null. */
    String activeToken(long deviceId);

    /** Marca el token como inválido (UNREGISTERED/INVALID_ARGUMENT de FCM). */
    void invalidate(String token);

    /** Info de diagnóstico SIN exponer el token completo (hash truncado). */
    TokenInfo info(long deviceId);

    /** Hash estable y corto del token para diagnóstico/auditoría. */
    static String tokenPrefix(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(12);
            for (int index = 0; index < 6; index++) {
                sb.append("%02x".formatted(digest[index] & 0xff));
            }
            return sb.toString();
        } catch (Exception error) {
            return "hash-error";
        }
    }
}
