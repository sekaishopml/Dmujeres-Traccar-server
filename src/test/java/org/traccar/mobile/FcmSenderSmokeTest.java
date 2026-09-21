package org.traccar.mobile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.traccar.config.Config;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * F2 smoke REAL opt-in contra FCM HTTP v1 (Admin SDK, ADC).
 *
 * Sólo se ejecuta si el operador exporta:
 *  - GOOGLE_APPLICATION_CREDENTIALS: ruta al JSON de la service account (nunca del repo/APK)
 *  - FCM_SMOKE_TOKEN: token FCM real de un dispositivo ya registrado (deviceid=47)
 *  - FCM_SMOKE_EXPECT (opcional): SUCCESS (default) | FAILURE | MISSING_CREDENTIALS
 *
 * Sin variables el test se salta (condition JUnit5 + assumption). NUNCA imprime el
 * token: la salida es exclusivamente status, messageId y errorCode (más contexto
 * deviceId/attemptId/issuedAt, que no son secretos).
 */
@EnabledIfEnvironmentVariable(named = "FCM_SMOKE_TOKEN", matches = ".+")
@EnabledIfEnvironmentVariable(named = "GOOGLE_APPLICATION_CREDENTIALS", matches = ".+")
public class FcmSenderSmokeTest {

    private static final String DEVICE_ID = "47";

    @Test
    void sendRecoveryProbeSmokeReal() {
        String token = System.getenv("FCM_SMOKE_TOKEN");
        assumeTrue(token != null && !token.isBlank(), "FCM_SMOKE_TOKEN requerido (opt-in)");
        assumeTrue(System.getenv("GOOGLE_APPLICATION_CREDENTIALS") != null,
                "GOOGLE_APPLICATION_CREDENTIALS requerido (ADC)");

        String expect = System.getenv().getOrDefault("FCM_SMOKE_EXPECT", "SUCCESS");
        String attemptId = UUID.randomUUID().toString();
        long issuedAtMs = System.currentTimeMillis();

        FcmSender sender = new FcmSender(new Config());
        FcmSender.SendResult result = sender.sendRecoveryProbe(token, attemptId, DEVICE_ID, issuedAtMs);

        // SOLO status/messageId/errorCode; NUNCA el token.
        System.out.println("FCM_SMOKE_CONTEXT deviceId=" + DEVICE_ID
                + " attemptId=" + attemptId
                + " issuedAtMs=" + issuedAtMs
                + " issuedAt=" + Instant.ofEpochMilli(issuedAtMs));
        System.out.println("FCM_SMOKE_RESULT status=" + result.status()
                + " messageId=" + result.messageId()
                + " errorCode=" + result.errorCode());

        assertNotNull(result.status(), "status nunca null");

        boolean hasMessageId = result.messageId() != null && !result.messageId().isBlank();
        if (hasMessageId) {
            assertNull(result.errorCode(), "messageId presente => sin errorCode");
        } else {
            assertNotNull(result.errorCode(), "SIN messageId no hay éxito: debe haber errorCode");
        }

        switch (expect) {
            case "SUCCESS" -> {
                assertEquals(FcmSender.Status.READY, result.status(),
                        "con credenciales válidas el sender debe quedar READY");
                assertTrue(hasMessageId, "smoke REAL esperaba messageId (SIN messageId no hay éxito)");
            }
            case "MISSING_CREDENTIALS" -> {
                assertEquals(FcmSender.Status.MISSING_CREDENTIALS, result.status());
                assertEquals("missing_credentials", result.errorCode());
            }
            case "FAILURE" -> assertTrue(!hasMessageId, "se esperaba fallo controlado sin messageId");
            default -> throw new IllegalArgumentException("FCM_SMOKE_EXPECT inválido: " + expect);
        }
    }
}
