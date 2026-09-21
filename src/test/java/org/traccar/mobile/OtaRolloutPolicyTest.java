/* Copyright 2026 Anton Tananaev (anton@traccar.org) */
package org.traccar.mobile;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R8: rollout OTA gradual. Rangos 0/100, pausa, forzado por versión mínima,
 * estabilidad del hash y distribución aproximada por buckets.
 */
public class OtaRolloutPolicyTest {

    private static final long LATEST = 130;
    private static final long NO_MIN = 0;

    @Test
    public void percentZeroDeniesEveryoneUnlessForced() {
        for (int i = 0; i < 200; i++) {
            String id = "equipo-" + i;
            assertEquals(OtaRolloutPolicy.Decision.DENIED,
                    OtaRolloutPolicy.decide(id, 120, LATEST, NO_MIN, 0, false, null));
        }
        assertEquals(OtaRolloutPolicy.Decision.FORCED,
                OtaRolloutPolicy.decide("equipo-1", 100, LATEST, 120, 0, false, null));
    }

    @Test
    public void percentHundredAllowsEveryone() {
        for (int i = 0; i < 200; i++) {
            String id = "equipo-" + i;
            assertEquals(OtaRolloutPolicy.Decision.ALLOWED,
                    OtaRolloutPolicy.decide(id, 120, LATEST, NO_MIN, 100, false, null));
        }
    }

    @Test
    public void pausedDeniesButForcedStillPasses() {
        assertEquals(OtaRolloutPolicy.Decision.DENIED,
                OtaRolloutPolicy.decide("equipo-7", 120, LATEST, NO_MIN, 100, true, null));
        assertEquals(OtaRolloutPolicy.Decision.FORCED,
                OtaRolloutPolicy.decide("equipo-7", 100, LATEST, 120, 100, true, null));
    }

    @Test
    public void installedBelowMinimumIsAlwaysForced() {
        assertEquals(OtaRolloutPolicy.Decision.FORCED,
                OtaRolloutPolicy.decide("equipo-3", 119, LATEST, 120, 5, false, null));
        // Igual al mínimo ya NO es forzado: manda el porcentaje.
        assertNotEquals(OtaRolloutPolicy.Decision.FORCED,
                OtaRolloutPolicy.decide("equipo-3", 120, LATEST, 120, 5, false, null));
    }

    @Test
    public void sameDeviceAlwaysGetsSameDecision() {
        OtaRolloutPolicy.Decision first = OtaRolloutPolicy.decide("moto-42", 120, LATEST, NO_MIN, 37, false, null);
        for (int i = 0; i < 500; i++) {
            assertEquals(first, OtaRolloutPolicy.decide("moto-42", 120, LATEST, NO_MIN, 37, false, null));
        }
        assertEquals(OtaRolloutPolicy.bucket("moto-42"), OtaRolloutPolicy.bucket("moto-42"));
        assertNotEquals(OtaRolloutPolicy.bucket("moto-42"), OtaRolloutPolicy.bucket("moto-43"));
    }

    @Test
    public void bucketsAreInRangeAndSpread() {
        Set<Integer> buckets = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            int bucket = OtaRolloutPolicy.bucket("device-" + i);
            assertTrue(bucket >= 0 && bucket < OtaRolloutPolicy.BUCKETS, "bucket fuera de rango: " + bucket);
            buckets.add(bucket);
        }
        // 1000 ids sobre 100 buckets deben tocar todos (colisión SHA-256 improbable).
        assertEquals(OtaRolloutPolicy.BUCKETS, buckets.size());
        // Nulo/vacío no revienta y es estable.
        assertEquals(OtaRolloutPolicy.bucket(null), OtaRolloutPolicy.bucket(""));
    }

    @Test
    public void distributionApproximatesRolloutPercent() {
        int total = 10_000;
        int allowed = 0;
        for (int i = 0; i < total; i++) {
            if (OtaRolloutPolicy.decide("flota-" + i, 120, LATEST, NO_MIN, 30, false, null)
                    == OtaRolloutPolicy.Decision.ALLOWED) {
                allowed++;
            }
        }
        double ratio = allowed / (double) total;
        assertTrue(ratio > 0.25 && ratio < 0.35, "distribución 30% fuera de rango: " + ratio);
    }

    @Test
    public void decisionIsMonotonicWhenPercentGrows() {
        // Subir el porcentaje solo agrega equipos: quien entraba a 20 sigue a 40.
        for (int i = 0; i < 500; i++) {
            String id = "flota-" + i;
            boolean at20 = OtaRolloutPolicy.decide(id, 120, LATEST, NO_MIN, 20, false, null)
                    == OtaRolloutPolicy.Decision.ALLOWED;
            boolean at40 = OtaRolloutPolicy.decide(id, 120, LATEST, NO_MIN, 40, false, null)
                    == OtaRolloutPolicy.Decision.ALLOWED;
            if (at20) {
                assertTrue(at40, "el bucket cambió al subir el porcentaje: " + id);
            }
        }
    }
}
