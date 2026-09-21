package org.traccar.mobile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * FASE 5 (§6, §40): motor de continuidad — fórmulas explícitas, sin ocultar
 * denominadores.
 *
 * - journeyTime   = último fix - primer fix de la ventana consultada.
 * - trackingTime  = Σ min(dt, GAP_THRESHOLD_MS) entre fixes consecutivos
 *                   (el tiempo cubierto por evidencia real; un hueco solo
 *                   aporta el umbral, el resto es gap).
 * - gapTime       = journeyTime - trackingTime.
 * - continuityPct = trackingTime / journeyTime × 100 (denominador explícito).
 * - Data integrity se reporta APARTE y solo si es medible: este motor no
 *   inventa un 100%.
 *
 * Un gap es un dt > GAP_THRESHOLD_MS. Nunca se interpola ni se rellena.
 */
public final class MobileContinuityPolicy {

    /** Hueco de datos: más de 5 min sin posición aceptada. */
    public static final long GAP_THRESHOLD_MS = 5 * 60_000L;

    public record Gap(long startMs, long endMs) {
        public long durationMs() {
            return endMs - startMs;
        }
    }

    public record SessionSpan(String sessionId, long firstMs, long lastMs, int fixes) {}

    public record Summary(
            long journeyTimeMs,
            long trackingTimeMs,
            long gapTimeMs,
            double continuityPct,
            int fixes,
            long firstMs,
            long lastMs,
            long gapThresholdMs,
            List<Gap> gaps,
            List<SessionSpan> sessions) {}

    private MobileContinuityPolicy() {
    }

    /** Calcula el resumen sobre tiempos de fix (ms), en cualquier orden. */
    public static Summary compute(List<Long> fixTimesMs) {
        if (fixTimesMs == null || fixTimesMs.isEmpty()) {
            return new Summary(0, 0, 0, 0.0, 0, 0, 0, GAP_THRESHOLD_MS, List.of(), List.of());
        }
        Set<Long> unique = new LinkedHashSet<>(fixTimesMs);
        List<Long> sorted = new ArrayList<>(unique);
        sorted.sort(Long::compareTo);
        if (sorted.size() == 1) {
            long only = sorted.get(0);
            return new Summary(0, 0, 0, 0.0, 1, only, only, GAP_THRESHOLD_MS, List.of(), List.of());
        }
        long first = sorted.get(0);
        long last = sorted.get(sorted.size() - 1);
        long journey = last - first;
        long tracking = 0;
        List<Gap> gaps = new ArrayList<>();
        for (int index = 1; index < sorted.size(); index++) {
            long dt = sorted.get(index) - sorted.get(index - 1);
            tracking += Math.min(dt, GAP_THRESHOLD_MS);
            if (dt > GAP_THRESHOLD_MS) {
                gaps.add(new Gap(sorted.get(index - 1), sorted.get(index)));
            }
        }
        gaps.sort((a, b) -> Long.compare(b.durationMs(), a.durationMs()));
        double continuity = journey > 0 ? (100.0 * tracking) / journey : 0.0;
        return new Summary(
                journey, tracking, Math.max(0, journey - tracking), continuity,
                sorted.size(), first, last, GAP_THRESHOLD_MS, gaps, List.of());
    }

    /** Continuidad con desglose por sesión (misma fórmula por sesión). */
    public static Summary compute(List<Long> fixTimesMs, List<SessionSpan> sessions) {
        Summary base = compute(fixTimesMs);
        return new Summary(
                base.journeyTimeMs(), base.trackingTimeMs(), base.gapTimeMs(), base.continuityPct(),
                base.fixes(), base.firstMs(), base.lastMs(), base.gapThresholdMs(), base.gaps(), sessions);
    }
}
