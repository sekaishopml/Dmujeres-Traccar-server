package org.traccar.mobile;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests del watchdog derivado (F0): umbrales explicables, sin valores
 * arbitrarios, y nunca declarando proceso muerto por silencio simple.
 */
class WatchdogPolicyTest {

    @Test
    void movingUsaUmbralCorto() {
        Assertions.assertEquals(240_000L, WatchdogPolicy.stalledThresholdMs(true));
    }

    @Test
    void stationaryUsaUmbralCorto() {
        Assertions.assertEquals(360_000L, WatchdogPolicy.stalledThresholdMs(false));
    }

    @Test
    void explainIncluyeModoYUmbral() {
        String explain = WatchdogPolicy.explain(true, 310_000L);
        Assertions.assertTrue(explain.contains("modo=moving"));
        Assertions.assertTrue(explain.contains("umbral=240s"));
        Assertions.assertTrue(explain.contains("silencio=310s"));
    }

    @Test
    void derivacionEsCoherenteConPoliticasDeLaApp() {
        // Derivación documentada R7 (pantalla apagada: duty base 120 s):
        // moving 120 s × 2 = 240 s; stationary 120 s × 3 = 360 s. Sin números
        // huérfanos (antes eran 300 s = 60×5 s y 900 s = 7.5×120 s).
        Assertions.assertEquals(
            WatchdogPolicy.STATIONARY_INTERVAL_S * 2 * 1000L, WatchdogPolicy.MOVING_THRESHOLD_MS);
        Assertions.assertEquals(
            WatchdogPolicy.STATIONARY_INTERVAL_S * 3 * 1000L, WatchdogPolicy.STATIONARY_THRESHOLD_MS);
    }
}
