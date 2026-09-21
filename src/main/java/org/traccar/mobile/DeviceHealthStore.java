package org.traccar.mobile;

/**
 * FASE 7 (§21): almacén de snapshots de salud por dispositivo
 * (tc_device_health). Interfaz para tests con implementaciones en memoria;
 * la implementación real es JDBC.
 *
 * Idempotencia: la clave natural es (deviceId, ts) — reenviar el mismo
 * snapshot (reintento de red) no duplica filas.
 */
public interface DeviceHealthStore {

    /** Snapshot tal como lo envía el cliente (sin coordenadas ni PII). */
    record Snapshot(
            long deviceId,
            long tsMs,
            String sessionId,
            Long wallBucket,
            Long elapsedMs,
            String eventType,
            String reason,
            String healthState,
            boolean fgs,
            String motion,
            String network,
            int outbox,
            /** F0: embudo del bucket (JSON compacto) o null. */
            String funnel) {}

    /** Perfil del dispositivo en el momento de la ingesta (enriquecido server-side). */
    record DeviceProfile(
            String manufacturer,
            String model,
            String androidVersion,
            String appVersion,
            String healthState,
            Long lastFixAtMs,
            String readiness,
            String continuity,
            String continuityCause,
            String recovery) {}

    /**
     * Inserta el snapshot si (deviceId, ts) no existe.
     *
     * @return true si insertó una fila nueva; false si ya existía (duplicado).
     */
    boolean insert(Snapshot snapshot, DeviceProfile profile);

    /** Borra filas anteriores a cutoffMs. @return filas borradas. */
    int pruneOlderThan(long cutoffMs);
}
