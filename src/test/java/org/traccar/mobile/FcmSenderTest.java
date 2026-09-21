package org.traccar.mobile;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.config.Config;
import org.traccar.config.Keys;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F2: auditoría del emisor FCM Recovery. Sin credenciales reales (este entorno
 * no tiene GOOGLE_APPLICATION_CREDENTIALS ni notificator.firebase.serviceAccount
 * configurado), el sender degrada a MISSING_CREDENTIALS sin crash y el probe
 * se bloquea de forma honesta. Los envíos se validan con un
 * FirebaseMessaging mockeado (payload data-only, HIGH, TTL 120 s y
 * clasificación de errores por MessagingErrorCode).
 */
public class FcmSenderTest {

    /** Config sin serviceAccount: fuerza la ruta Application Default Credentials. */
    private static FcmSender senderSinCredenciales() {
        Config config = mock(Config.class);
        when(config.getString(Keys.NOTIFICATOR_FIREBASE_SERVICE_ACCOUNT)).thenReturn(null);
        return new FcmSender(config);
    }

    // ------------------------------------------------------------------
    // (a) estado sin credenciales
    // ------------------------------------------------------------------

    @Test
    void statusSinCredencialesEsMissingCredentialsYNoCrash() {
        FcmSender sender = senderSinCredenciales();
        assertEquals(FcmSender.Status.MISSING_CREDENTIALS, sender.status());
        // idempotente: no reintenta ni lanza en llamadas repetidas
        assertEquals(FcmSender.Status.MISSING_CREDENTIALS, sender.status());
    }

    // ------------------------------------------------------------------
    // (b) sendRecoveryProbe sin messaging inicializado
    // ------------------------------------------------------------------

    @Test
    void sendRecoveryProbeSinCredencialesDevuelveMissingCredentialsSinExcepcion() {
        FcmSender sender = senderSinCredenciales();

        FcmSender.SendResult result = assertDoesNotThrow(
                () -> sender.sendRecoveryProbe("token-fake", "fcm-attempt-1", "48", 1_760_000_000_000L));

        assertEquals(FcmSender.Status.MISSING_CREDENTIALS, result.status());
        assertNull(result.messageId());
        assertEquals("missing_credentials", result.errorCode());
        assertFalse(result.invalidToken());
    }

    // ------------------------------------------------------------------
    // (c) mensaje sin token: nunca lanza
    // ------------------------------------------------------------------

    @Test
    void mensajeSinTokenNoLanzaNiReportaExito() throws Exception {
        FcmSender sender = senderSinCredenciales();
        FirebaseMessaging messaging = mock(FirebaseMessaging.class);
        // El SDK real rechaza un mensaje sin token/topic/condition.
        when(messaging.send(any(Message.class))).thenThrow(
                new IllegalArgumentException("Exactly one of token, topic or condition must be specified"));
        injectMessaging(sender, messaging);

        FcmSender.SendResult result = assertDoesNotThrow(
                () -> sender.sendRecoveryProbe(null, "fcm-attempt-2", "48", 1L));

        assertNull(result.messageId(), "sin messageId jamás hay éxito");
        assertEquals("IllegalArgumentException", result.errorCode());
        assertFalse(result.invalidToken());
    }

    @Test
    void messageIdVacioNoEsExito() throws Exception {
        FcmSender sender = senderSinCredenciales();
        FirebaseMessaging messaging = mock(FirebaseMessaging.class);
        when(messaging.send(any(Message.class))).thenReturn(null);
        injectMessaging(sender, messaging);

        FcmSender.SendResult result = sender.sendRecoveryProbe("token-fake", "fcm-attempt-3", "48", 1L);

        assertNull(result.messageId());
        assertEquals("empty_message_id", result.errorCode());
    }

    // ------------------------------------------------------------------
    // Payload: data-only, HIGH, TTL 120 s, mínimo (4 claves, sin PII)
    // ------------------------------------------------------------------

    @Test
    void payloadEsDataOnlyMinimoConHighYTtl120Segundos() throws Exception {
        FcmSender sender = senderSinCredenciales();
        FirebaseMessaging messaging = mock(FirebaseMessaging.class);
        when(messaging.send(any(Message.class))).thenReturn("projects/dmj/messages/abc");
        injectMessaging(sender, messaging);

        long issuedAt = 1_760_000_000_000L;
        FcmSender.SendResult result = sender.sendRecoveryProbe(
                "token-fake", "fcm-attempt-4", "48", issuedAt);

        assertEquals(FcmSender.Status.READY, result.status());
        assertEquals("projects/dmj/messages/abc", result.messageId());
        assertNull(result.errorCode());
        assertFalse(result.invalidToken());

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messaging).send(captor.capture());
        Message sent = captor.getValue();

        // data-only: sin notification, sin APNs/WebPush, sin topic/condition
        assertNull(field(sent, "notification"));
        assertNull(field(sent, "apnsConfig"));
        assertNull(field(sent, "webpushConfig"));
        assertNull(field(sent, "topic"));
        assertNull(field(sent, "condition"));
        assertEquals("token-fake", field(sent, "token"));

        @SuppressWarnings("unchecked")
        Map<String, String> data = (Map<String, String>) field(sent, "data");
        assertEquals(4, data.size(), "payload mínimo: solo type/recoveryAttemptId/deviceId/issuedAt");
        assertEquals("TRACKING_RECOVERY_PROBE", data.get("type"));
        assertEquals("fcm-attempt-4", data.get("recoveryAttemptId"));
        assertEquals("48", data.get("deviceId"));
        assertEquals(String.valueOf(issuedAt), data.get("issuedAt"));

        AndroidConfig android = (AndroidConfig) field(sent, "androidConfig");
        assertNotNull(android);
        assertNull(field(android, "notification"));
        assertEquals("high", field(android, "priority"));
        assertEquals("120s", field(android, "ttl"));
    }

    // ------------------------------------------------------------------
    // Clasificación de errores FCM
    // ------------------------------------------------------------------

    @Test
    void unregisteredEInvalidArgumentMarcanInvalidToken() throws Exception {
        assertInvalidToken(MessagingErrorCode.UNREGISTERED, true);
        assertInvalidToken(MessagingErrorCode.INVALID_ARGUMENT, true);
        assertInvalidToken(MessagingErrorCode.INTERNAL, false);
    }

    private void assertInvalidToken(MessagingErrorCode code, boolean expectedInvalid) throws Exception {
        FcmSender sender = senderSinCredenciales();
        FirebaseMessaging messaging = mock(FirebaseMessaging.class);
        FirebaseMessagingException failure = mock(FirebaseMessagingException.class);
        when(failure.getMessagingErrorCode()).thenReturn(code);
        when(messaging.send(any(Message.class))).thenThrow(failure);
        injectMessaging(sender, messaging);

        FcmSender.SendResult result = sender.sendRecoveryProbe("token-fake", "fcm-attempt-5", "48", 1L);

        assertNull(result.messageId());
        assertEquals(code.name(), result.errorCode());
        assertEquals(expectedInvalid, result.invalidToken(), "invalidToken para " + code);
    }

    // ------------------------------------------------------------------
    // Helpers: el SDK no expone getters públicos en Message/AndroidConfig
    // ------------------------------------------------------------------

    private static void injectMessaging(FcmSender sender, FirebaseMessaging messaging) throws Exception {
        // Consume la init lazy (falla sin credenciales) ANTES de inyectar el mock.
        sender.status();
        Field field = FcmSender.class.getDeclaredField("messaging");
        field.setAccessible(true);
        field.set(sender, messaging);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
