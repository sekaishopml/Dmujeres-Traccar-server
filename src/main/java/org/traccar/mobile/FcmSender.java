package org.traccar.mobile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.auth.oauth2.GoogleCredentials;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;

import org.traccar.config.Config;
import org.traccar.config.Keys;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * F2: emisor FCM Recovery (cliente Admin SDK dedicado, lazy). NO duplica
 * NotificatorFirebase (ese envía notificaciones de eventos a usuarios);
 * este envía el probe de recovery a un token de DISPOSITIVO.
 *
 * Credenciales (en orden, sin hardcodear nada):
 * 1) {@code notificator.firebase.serviceAccount} si está configurado
 *    (mecanismo de despliegue existente del proyecto).
 * 2) Application Default Credentials: {@code GOOGLE_APPLICATION_CREDENTIALS}
 *    apuntando al JSON de la cuenta de servicio (fuera del repo y del APK).
 *
 * Degradación honesta: sin credenciales → status MISSING_CREDENTIALS con log
 * claro; el recovery queda bloqueado (nunca crash silencioso).
 */
@Singleton
public class FcmSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(FcmSender.class);
    private static final String APP_NAME = "dmj-recovery";

    public enum Status { READY, MISSING_CREDENTIALS, INIT_ERROR }

    public record SendResult(Status status, String messageId, String errorCode, boolean invalidToken) {}

    private final Config config;
    private FirebaseMessaging messaging;
    private Status status = Status.INIT_ERROR;
    private boolean initialized;

    @Inject
    public FcmSender(Config config) {
        this.config = config;
    }

    /** Inicialización lazy: nunca en el arranque si la feature está apagada. */
    private synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;
        try {
            FirebaseApp app = FirebaseApp.getApps().stream()
                    .filter(existing -> APP_NAME.equals(existing.getName()))
                    .findFirst()
                    .orElse(null);
            if (app == null) {
                String serviceAccount = config.getString(Keys.NOTIFICATOR_FIREBASE_SERVICE_ACCOUNT);
                FirebaseOptions options;
                if (serviceAccount != null && !serviceAccount.isBlank()) {
                    options = FirebaseOptions.builder()
                            .setCredentials(GoogleCredentials.fromStream(
                                    new ByteArrayInputStream(serviceAccount.getBytes(StandardCharsets.UTF_8))))
                            .build();
                    LOGGER.info("FCM recovery: credenciales desde notificator.firebase.serviceAccount");
                } else {
                    options = FirebaseOptions.builder()
                            .setCredentials(GoogleCredentials.getApplicationDefault())
                            .build();
                    LOGGER.info("FCM recovery: credenciales desde Application Default Credentials");
                }
                app = FirebaseApp.initializeApp(options, APP_NAME);
            }
            messaging = FirebaseMessaging.getInstance(app);
            status = Status.READY;
        } catch (IOException error) {
            messaging = null;
            status = Status.MISSING_CREDENTIALS;
            LOGGER.error("FCM recovery SIN credenciales utilizables "
                    + "(configura GOOGLE_APPLICATION_CREDENTIALS o "
                    + "notificator.firebase.serviceAccount); la feature queda bloqueada: {}",
                    error.getMessage());
        } catch (Exception error) {
            messaging = null;
            status = Status.INIT_ERROR;
            LOGGER.error("FCM recovery inicialización falló (status=INIT_ERROR): {}: {}",
                    error.getClass().getSimpleName(), error.getMessage());
        }
    }

    public Status status() {
        ensureInitialized();
        return status;
    }

    /**
     * Envía el probe de recovery: DATA message, prioridad HIGH, TTL corto.
     * Payload mínimo (sin ubicación ni datos sensibles).
     */
    public SendResult sendRecoveryProbe(String token, String recoveryAttemptId, String deviceId, long issuedAtMs) {
        ensureInitialized();
        if (messaging == null) {
            return new SendResult(Status.MISSING_CREDENTIALS, null, "missing_credentials", false);
        }
        try {
            Message message = Message.builder()
                    .setToken(token)
                    .setAndroidConfig(AndroidConfig.builder()
                            .setPriority(AndroidConfig.Priority.HIGH)
                            .setTtl(120_000L)
                            .build())
                    .putData("type", "TRACKING_RECOVERY_PROBE")
                    .putData("recoveryAttemptId", recoveryAttemptId)
                    .putData("deviceId", deviceId)
                    .putData("issuedAt", String.valueOf(issuedAtMs))
                    .build();
            String messageId = messaging.send(message);
            if (messageId == null || messageId.isBlank()) {
                // Nunca se reporta éxito sin messageId real del SDK.
                return new SendResult(Status.READY, null, "empty_message_id", false);
            }
            return new SendResult(Status.READY, messageId, null, false);
        } catch (FirebaseMessagingException error) {
            MessagingErrorCode code = error.getMessagingErrorCode();
            boolean invalidToken = code == MessagingErrorCode.UNREGISTERED
                    || code == MessagingErrorCode.INVALID_ARGUMENT;
            return new SendResult(Status.READY, null, code != null ? code.name() : error.getMessage(), invalidToken);
        } catch (Exception error) {
            return new SendResult(Status.READY, null, error.getClass().getSimpleName(), false);
        }
    }
}
