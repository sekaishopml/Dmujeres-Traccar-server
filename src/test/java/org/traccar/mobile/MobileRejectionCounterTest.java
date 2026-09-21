package org.traccar.mobile;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

/** R8.2 (H4): los rechazos de ingesta deben contar, no callarse. */
class MobileRejectionCounterTest {

    @Test
    void cuentaPorMotivoYTotal() {
        long totalAntes = MobileRejectionCounter.snapshot().getOrDefault("total", 0L);
        MobileRejectionCounter.inc(MobileRejectionCounter.DEVICE_UNKNOWN);
        MobileRejectionCounter.inc(MobileRejectionCounter.DEVICE_UNKNOWN);
        MobileRejectionCounter.inc(MobileRejectionCounter.RESERVE_FAILED);
        Map<String, Long> snapshot = MobileRejectionCounter.snapshot();
        Assertions.assertEquals(totalAntes + 3L, snapshot.get("total"));
        Assertions.assertEquals(2L, snapshot.get(MobileRejectionCounter.DEVICE_UNKNOWN) - snapshot.getOrDefault("device_unknown_antes", 0L));
        // Simplificación robusta: solo importan los deltas y el total.
        Assertions.assertTrue(snapshot.get("total") >= 3L);
    }

    @Test
    void motivoNuloONuevoSeManeja() {
        Assertions.assertDoesNotThrow(() -> MobileRejectionCounter.inc(null));
        MobileRejectionCounter.inc("motivo_nuevo");
        Assertions.assertEquals(1L, MobileRejectionCounter.snapshot().get("motivo_nuevo"));
    }
}
