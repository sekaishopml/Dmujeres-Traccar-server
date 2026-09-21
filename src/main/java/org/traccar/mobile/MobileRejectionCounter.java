package org.traccar.mobile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * R8.2 (H4): contador de rechazos de ingesta móvil. Antes, un REJECTED
 * (equipo desconocido, fallo de reserva, dedupe conflictivo, validación) no
 * dejaba rastro visible: el teléfono "no capturaba" y no se sabía que el
 * server descartó. Ahora cada rechazo queda contado por motivo y el panel de
 * alertas lo muestra (AdminAlertsService → system.ingestRejections).
 *
 * Contadores en memoria del proceso (se reinician con el server); el objetivo
 * es VISIBILIDAD inmediata, no auditoría histórica (para eso están los logs).
 */
public final class MobileRejectionCounter {

    public static final String DEVICE_UNKNOWN = "device_unknown";
    public static final String RESERVE_FAILED = "reserve_failed";
    public static final String DEDUPE_CONFLICT = "dedupe_conflict";
    public static final String VALIDATION_FAILED = "validation_failed";
    public static final String PROCESSING_FAILED = "processing_failed";

    private static final Map<String, AtomicLong> COUNTS = new LinkedHashMap<>();

    static {
        COUNTS.put(DEVICE_UNKNOWN, new AtomicLong());
        COUNTS.put(RESERVE_FAILED, new AtomicLong());
        COUNTS.put(DEDUPE_CONFLICT, new AtomicLong());
        COUNTS.put(VALIDATION_FAILED, new AtomicLong());
        COUNTS.put(PROCESSING_FAILED, new AtomicLong());
    }

    private MobileRejectionCounter() {
    }

    public static void inc(String reason) {
        AtomicLong counter = COUNTS.get(reason);
        if (reason == null) {
            return;
        }
        AtomicLong target = COUNTS.get(reason);
        if (target == null) {
            target = COUNTS.computeIfAbsent(reason, key -> new AtomicLong());
        }
        target.incrementAndGet();
    }

    /** Copia instantánea de los contadores (reason → total). */
    public static Map<String, Long> snapshot() {
        Map<String, Long> result = new LinkedHashMap<>();
        long total = 0L;
        for (Map.Entry<String, AtomicLong> entry : COUNTS.entrySet()) {
            long value = entry.getValue().get();
            if (value > 0L) {
                result.put(entry.getKey(), value);
            }
            total += value;
        }
        result.put("total", total);
        return result;
    }
}
