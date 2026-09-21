package org.traccar.mobile;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Set;

/**
 * F2: política de recovery FCM — elegibilidad, transiciones de evidencia y
 * REGLA CRÍTICA: SUCCESS solo con evidencia end-to-end (nunca por recibir FCM).
 */
public class FcmRecoveryPolicyTest {

    private static final long NOW = 1_760_000_000_000L;

    @Test
    void featureDisabledBloquea() {
        Assertions.assertEquals(FcmRecoveryPolicy.Decision.SKIP_DISABLED,
                FcmRecoveryPolicy.decide(false, true, false, 0L, 0, NOW, 900_000L, 3));
    }

    @Test
    void sinTokenBloquea() {
        Assertions.assertEquals(FcmRecoveryPolicy.Decision.SKIP_NO_TOKEN,
                FcmRecoveryPolicy.decide(true, false, false, 0L, 0, NOW, 900_000L, 3));
    }

    @Test
    void intentoActivoBloqueaDuplicado() {
        Assertions.assertEquals(FcmRecoveryPolicy.Decision.SKIP_ACTIVE_ATTEMPT,
                FcmRecoveryPolicy.decide(true, true, true, 0L, 0, NOW, 900_000L, 3));
    }

    @Test
    void cooldownBloquea() {
        Assertions.assertEquals(FcmRecoveryPolicy.Decision.SKIP_COOLDOWN,
                FcmRecoveryPolicy.decide(true, true, false, NOW - 60_000L, 1, NOW, 900_000L, 3));
    }

    @Test
    void rateLimitBloquea() {
        Assertions.assertEquals(FcmRecoveryPolicy.Decision.SKIP_RATE_LIMIT,
                FcmRecoveryPolicy.decide(true, true, false, NOW - 2_000_000L, 3, NOW, 900_000L, 3));
    }

    @Test
    void elegiblePermite() {
        Assertions.assertEquals(FcmRecoveryPolicy.Decision.ALLOW,
                FcmRecoveryPolicy.decide(true, true, false, NOW - 2_000_000L, 1, NOW, 900_000L, 3));
    }

    @Test
    void successRequiereEvidenciaCompleta() {
        // Solo FCM recibido NO es éxito.
        Assertions.assertFalse(FcmRecoveryPolicy.isSuccess(Set.of(
                FcmRecoveryPolicy.Stage.RECEIVED, FcmRecoveryPolicy.Stage.STARTED)));
        // Solo servicio iniciado NO es éxito.
        Assertions.assertFalse(FcmRecoveryPolicy.isSuccess(Set.of(
                FcmRecoveryPolicy.Stage.RECEIVED, FcmRecoveryPolicy.Stage.STARTED,
                FcmRecoveryPolicy.Stage.FGS_ACTIVE)));
        // Evidencia completa → éxito.
        Assertions.assertTrue(FcmRecoveryPolicy.isSuccess(Set.of(
                FcmRecoveryPolicy.Stage.RECEIVED, FcmRecoveryPolicy.Stage.STARTED,
                FcmRecoveryPolicy.Stage.FGS_ACTIVE, FcmRecoveryPolicy.Stage.TRACKING_ACTIVE,
                FcmRecoveryPolicy.Stage.GPS_CONFIRMED, FcmRecoveryPolicy.Stage.SERVER_ACK)));
    }

    @Test
    void transicionesValidasEnOrden() {
        Assertions.assertTrue(FcmRecoveryPolicy.isValidTransition(Set.of(), FcmRecoveryPolicy.Stage.RECEIVED));
        Assertions.assertTrue(FcmRecoveryPolicy.isValidTransition(
                Set.of(FcmRecoveryPolicy.Stage.RECEIVED), FcmRecoveryPolicy.Stage.STARTED));
        Assertions.assertTrue(FcmRecoveryPolicy.isValidTransition(
                Set.of(FcmRecoveryPolicy.Stage.RECEIVED, FcmRecoveryPolicy.Stage.STARTED),
                FcmRecoveryPolicy.Stage.FGS_ACTIVE));
        // Salto inválido: STARTED sin RECEIVED.
        Assertions.assertFalse(FcmRecoveryPolicy.isValidTransition(Set.of(), FcmRecoveryPolicy.Stage.STARTED));
        // FGS_ACTIVE sin STARTED.
        Assertions.assertFalse(FcmRecoveryPolicy.isValidTransition(
                Set.of(FcmRecoveryPolicy.Stage.RECEIVED), FcmRecoveryPolicy.Stage.FGS_ACTIVE));
    }

    @Test
    void ackDuplicadoEsIdempotenteRechazado() {
        Set<FcmRecoveryPolicy.Stage> confirmed = Set.of(FcmRecoveryPolicy.Stage.RECEIVED);
        Assertions.assertFalse(FcmRecoveryPolicy.isValidTransition(
                confirmed, FcmRecoveryPolicy.Stage.RECEIVED));
    }

    @Test
    void serverAckNuncaEsAckDelCliente() {
        // Aunque el cliente tenga toda la evidencia previa, SERVER_ACK sólo lo
        // fija el servidor al recibir una posición real: el ACK debe rechazarse.
        Set<FcmRecoveryPolicy.Stage> confirmed = Set.of(
                FcmRecoveryPolicy.Stage.RECEIVED, FcmRecoveryPolicy.Stage.STARTED,
                FcmRecoveryPolicy.Stage.FGS_ACTIVE, FcmRecoveryPolicy.Stage.TRACKING_ACTIVE,
                FcmRecoveryPolicy.Stage.GPS_CONFIRMED);
        Assertions.assertFalse(FcmRecoveryPolicy.isValidTransition(
                confirmed, FcmRecoveryPolicy.Stage.SERVER_ACK));
    }

    @Test
    void timeoutsReportanCausaCorrecta() {
        Assertions.assertEquals("TIMEOUT_NO_DELIVERY",
                FcmRecoveryPolicy.timeoutReason(Set.of(FcmRecoveryPolicy.Stage.ATTEMPT)));
        Assertions.assertEquals("TIMEOUT_NO_FGS", FcmRecoveryPolicy.timeoutReason(Set.of(
                FcmRecoveryPolicy.Stage.RECEIVED, FcmRecoveryPolicy.Stage.STARTED)));
        Assertions.assertEquals("TIMEOUT_NO_TRACKING", FcmRecoveryPolicy.timeoutReason(Set.of(
                FcmRecoveryPolicy.Stage.RECEIVED, FcmRecoveryPolicy.Stage.STARTED,
                FcmRecoveryPolicy.Stage.FGS_ACTIVE)));
        Assertions.assertEquals("TIMEOUT_NO_GPS", FcmRecoveryPolicy.timeoutReason(Set.of(
                FcmRecoveryPolicy.Stage.RECEIVED, FcmRecoveryPolicy.Stage.STARTED,
                FcmRecoveryPolicy.Stage.FGS_ACTIVE, FcmRecoveryPolicy.Stage.TRACKING_ACTIVE)));
        Assertions.assertEquals("TIMEOUT_NO_SERVER_ACK", FcmRecoveryPolicy.timeoutReason(Set.of(
                FcmRecoveryPolicy.Stage.RECEIVED, FcmRecoveryPolicy.Stage.STARTED,
                FcmRecoveryPolicy.Stage.FGS_ACTIVE, FcmRecoveryPolicy.Stage.TRACKING_ACTIVE,
                FcmRecoveryPolicy.Stage.GPS_CONFIRMED)));
    }

    @Test
    void prioridadNormalizada() {
        Assertions.assertEquals("HIGH", FcmRecoveryPolicy.priorityLabel("high"));
        Assertions.assertEquals("NORMAL", FcmRecoveryPolicy.priorityLabel("NORMAL"));
        Assertions.assertEquals("UNKNOWN", FcmRecoveryPolicy.priorityLabel(null));
    }

    @Test
    void tokenPrefixNoExponeTokenCompleto() {
        String prefix = FcmTokenStore.tokenPrefix("token-secreto-123");
        Assertions.assertEquals(12, prefix.length());
        Assertions.assertFalse("token-secreto-123".contains(prefix));
    }

    @Test
    void softSuccessWindowIsInclusiveAndRejectsInvalid() {
        Assertions.assertTrue(FcmRecoveryPolicy.isSoftSuccess(0L));
        Assertions.assertTrue(FcmRecoveryPolicy.isSoftSuccess(300_000L));
        Assertions.assertFalse(FcmRecoveryPolicy.isSoftSuccess(300_001L));
        Assertions.assertFalse(FcmRecoveryPolicy.isSoftSuccess(-1L));
    }
}
