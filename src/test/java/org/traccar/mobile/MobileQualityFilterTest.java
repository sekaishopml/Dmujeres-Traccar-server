/* Copyright 2026 Anton Tananaev (anton@traccar.org) */
package org.traccar.mobile;

import org.junit.jupiter.api.Test;
import org.traccar.model.Position;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas puras de MobileQualityFilter (sin DI ni base de datos).
 */
public class MobileQualityFilterTest {

    private static final double HIDE = 80.0;
    private static final double REJECT = 500.0;
    private static final double MAX_SPEED_KN = 140.0;

    @Test
    public void testRejectAbsurdAccuracy() {
        Position position = position(0.0, 0.0, 1_700_000_000_000L, 600.0);
        MobileQualityFilter.Verdict verdict =
                MobileQualityFilter.assess(position, null, HIDE, REJECT, MAX_SPEED_KN);
        assertEquals(MobileQualityFilter.Verdict.REJECT, verdict);
    }

    @Test
    public void testHideIntermediateAccuracy() {
        Position position = position(0.0, 0.0, 1_700_000_000_000L, 150.0);
        MobileQualityFilter.Verdict verdict =
                MobileQualityFilter.assess(position, null, HIDE, REJECT, MAX_SPEED_KN);
        assertEquals(MobileQualityFilter.Verdict.HIDE, verdict);
        assertFalse(position.getValid());
    }

    @Test
    public void testAbsurdSpeedMarksInvalidWithoutReject() {
        // ~10 km en 60 s ≈ 324 kn > 140 kn: salto absurdo → invalid, nunca REJECT.
        Position last = position(0.0, 0.0, 1_700_000_000_000L, 10.0);
        Position position = position(0.09, 0.0, 1_700_000_000_000L + 60_000L, 10.0);
        MobileQualityFilter.Verdict verdict =
                MobileQualityFilter.assess(position, last, HIDE, REJECT, MAX_SPEED_KN);
        assertEquals(MobileQualityFilter.Verdict.HIDE, verdict);
        assertFalse(position.getValid());
    }

    @Test
    public void testOrderedReplayIsAccepted() {
        // ~10 m en 10 s ≈ 2 kn < 140 kn: replay ordenado por fixtime OK.
        Position last = position(0.0, 0.0, 1_700_000_000_000L, 10.0);
        Position position = position(0.00009, 0.0, 1_700_000_000_000L + 10_000L, 10.0);
        MobileQualityFilter.Verdict verdict =
                MobileQualityFilter.assess(position, last, HIDE, REJECT, MAX_SPEED_KN);
        assertEquals(MobileQualityFilter.Verdict.ACCEPT, verdict);
        assertTrue(position.getValid());
    }

    @Test
    public void testUnknownAccuracyWithoutPreviousIsAccepted() {
        // Sin setAccuracy (0.0 = desconocida) y sin previa: no se rechaza ni se oculta.
        Position position = new Position("dmj-mqtt");
        position.setDeviceId(1L);
        position.setLatitude(0.0);
        position.setLongitude(0.0);
        position.setTime(new Date(1_700_000_000_000L));
        position.setValid(true);
        MobileQualityFilter.Verdict verdict =
                MobileQualityFilter.assess(position, null, HIDE, REJECT, MAX_SPEED_KN);
        assertEquals(MobileQualityFilter.Verdict.ACCEPT, verdict);
        assertTrue(position.getValid());
    }

    @Test
    public void testUnknownAccuracyWithSpeedJumpMarksInvalid() {
        // Accuracy desconocida + salto de velocidad → valid=false (nunca REJECT).
        Position last = position(0.0, 0.0, 1_700_000_000_000L, 10.0);
        Position position = new Position("dmj-mqtt");
        position.setDeviceId(1L);
        position.setLatitude(0.09);
        position.setLongitude(0.0);
        position.setTime(new Date(1_700_000_000_000L + 60_000L));
        position.setValid(true);
        MobileQualityFilter.Verdict verdict =
                MobileQualityFilter.assess(position, last, HIDE, REJECT, MAX_SPEED_KN);
        assertEquals(MobileQualityFilter.Verdict.HIDE, verdict);
        assertFalse(position.getValid());
    }

    @Test
    public void testExactRelayIsDuplicate() {
        // Re-entrega cacheada: misma coordenada exacta + speed 0 dentro de 120 s.
        Position last = position(0.0, 0.0, 1_700_000_000_000L, 10.0);
        Position position = position(0.0, 0.0, 1_700_000_000_000L + 60_000L, 12.0);
        MobileQualityFilter.Verdict verdict =
                MobileQualityFilter.assess(position, last, HIDE, REJECT, MAX_SPEED_KN);
        assertEquals(MobileQualityFilter.Verdict.DUPLICATE, verdict);
        assertTrue(position.getValid());
    }

    @Test
    public void testExactRelayOutsideWindowIsAccepted() {
        Position last = position(0.0, 0.0, 1_700_000_000_000L, 10.0);
        Position position = position(0.0, 0.0, 1_700_000_000_000L + 121_000L, 10.0);
        MobileQualityFilter.Verdict verdict =
                MobileQualityFilter.assess(position, last, HIDE, REJECT, MAX_SPEED_KN);
        assertEquals(MobileQualityFilter.Verdict.ACCEPT, verdict);
    }

    @Test
    public void testRealGpsDriftIsNotDuplicate() {
        // Un receptor real nunca repite la coordenada al centímetro: 1 m de deriva acepta.
        Position last = position(0.0, 0.0, 1_700_000_000_000L, 10.0);
        Position position = position(0.000009, 0.0, 1_700_000_000_000L + 10_000L, 10.0);
        MobileQualityFilter.Verdict verdict =
                MobileQualityFilter.assess(position, last, HIDE, REJECT, MAX_SPEED_KN);
        assertEquals(MobileQualityFilter.Verdict.ACCEPT, verdict);
    }

    @Test
    public void testMovingFixWithSameCoordsIsAccepted() {
        Position last = position(0.0, 0.0, 1_700_000_000_000L, 10.0);
        Position position = position(0.0, 0.0, 1_700_000_000_000L + 10_000L, 10.0);
        position.setSpeed(5.0);
        MobileQualityFilter.Verdict verdict =
                MobileQualityFilter.assess(position, last, HIDE, REJECT, MAX_SPEED_KN);
        assertEquals(MobileQualityFilter.Verdict.ACCEPT, verdict);
    }

    @Test
    public void testIsExactRelayNullSafe() {
        assertFalse(MobileQualityFilter.isExactRelay(position(0.0, 0.0, 1L, 10.0), null));
    }

    private static Position position(double latitude, double longitude, long fixTimeMs, double accuracy) {
        Position position = new Position("dmj-mqtt");
        position.setDeviceId(1L);
        position.setLatitude(latitude);
        position.setLongitude(longitude);
        position.setTime(new Date(fixTimeMs));
        position.setAccuracy(accuracy);
        position.setValid(true);
        return position;
    }
}
