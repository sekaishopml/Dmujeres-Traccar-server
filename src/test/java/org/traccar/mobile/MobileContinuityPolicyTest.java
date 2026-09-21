/* Copyright 2026 Anton Tananaev (anton@traccar.org) */
package org.traccar.mobile;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FASE 5: fórmulas de continuidad. Caracteriza denominadores y el caso real
 * qa-f0 (jornada ~11h27 con hueco ~10h29) sin inventar precisión.
 */
public class MobileContinuityPolicyTest {

    private static final long MIN = 60_000L;
    private static final long HOUR = 3_600_000L;

    @Test
    public void testEmptyAndSingleFixAreZeroWithoutInventing() {
        MobileContinuityPolicy.Summary empty = MobileContinuityPolicy.compute(List.of());
        assertEquals(0, empty.fixes());
        assertEquals(0, empty.journeyTimeMs());
        assertEquals(0.0, empty.continuityPct());

        MobileContinuityPolicy.Summary single = MobileContinuityPolicy.compute(List.of(1_000L));
        assertEquals(1, single.fixes());
        assertEquals(0, single.journeyTimeMs());
        assertEquals(0.0, single.continuityPct());
        assertTrue(single.gaps().isEmpty());
    }

    @Test
    public void testJourneyTrackingGapAndContinuity() {
        // Jornada de 12 h con tracking denso de 10 h y un hueco de 2 h.
        List<Long> fixes = new ArrayList<>();
        long start = 0L;
        for (long t = 0; t <= 10 * HOUR; t += 30_000L) {
            fixes.add(start + t);
        }
        // reanuda a las 12 h (hueco de 2 h)
        fixes.add(start + 12 * HOUR);
        MobileContinuityPolicy.Summary summary = MobileContinuityPolicy.compute(fixes);
        assertEquals(12 * HOUR, summary.journeyTimeMs());
        // 10 h densos + el hueco aporta solo el umbral (5 min), no 2 h.
        assertEquals(10 * HOUR + 5 * MIN, summary.trackingTimeMs());
        assertEquals(2 * HOUR - 5 * MIN, summary.gapTimeMs());
        assertEquals(100.0 * (10 * HOUR + 5 * MIN) / (12 * HOUR), summary.continuityPct(), 0.01);
        assertEquals(1, summary.gaps().size());
        assertEquals(2 * HOUR, summary.gaps().get(0).durationMs());
    }

    @Test
    public void testQaF0RealShape() {
        // Caso real: ~1 h 02 de tracking dentro de una jornada de ~11 h 27.
        List<Long> fixes = new ArrayList<>();
        long start = 0L;
        for (long t = 0; t < HOUR; t += 60_000L) {
            fixes.add(start + t);
        }
        fixes.add(start + 11 * HOUR + 27 * MIN);
        MobileContinuityPolicy.Summary summary = MobileContinuityPolicy.compute(fixes);
        assertEquals(11 * HOUR + 27 * MIN, summary.journeyTimeMs());
        // Σ min(dt,5min): 59 intervalos de 1 min + el hueco aporta 5 min.
        assertEquals(59 * MIN + 5 * MIN, summary.trackingTimeMs());
        assertEquals(1, summary.gaps().size());
        assertEquals(11 * HOUR + 27 * MIN - 59 * MIN, summary.gaps().get(0).durationMs());
        assertTrue(summary.continuityPct() < 10.0);
        assertTrue(summary.continuityPct() > 0.0);
    }

    @Test
    public void testDuplicatesDoNotInflateJourneyAndThresholdIsExplicit() {
        List<Long> fixes = List.of(1_000L, 1_000L, 2_000L, 2_000L, 400_000L);
        MobileContinuityPolicy.Summary summary = MobileContinuityPolicy.compute(fixes);
        assertEquals(3, summary.fixes());
        assertEquals(399_000L, summary.journeyTimeMs());
        assertEquals(1_000L + MobileContinuityPolicy.GAP_THRESHOLD_MS, summary.trackingTimeMs());
        assertEquals(MobileContinuityPolicy.GAP_THRESHOLD_MS, summary.gapThresholdMs());
    }

    @Test
    public void testSessionSpansAreAttachedWithoutChangingTotals() {
        MobileContinuityPolicy.Summary base = MobileContinuityPolicy.compute(List.of(0L, HOUR));
        MobileContinuityPolicy.Summary withSessions = MobileContinuityPolicy.compute(
                List.of(0L, HOUR),
                List.of(new MobileContinuityPolicy.SessionSpan("s1", 0L, HOUR, 2)));
        assertEquals(base.journeyTimeMs(), withSessions.journeyTimeMs());
        assertEquals(base.continuityPct(), withSessions.continuityPct(), 0.0001);
        assertEquals(1, withSessions.sessions().size());
        assertEquals("s1", withSessions.sessions().get(0).sessionId());
    }
}
