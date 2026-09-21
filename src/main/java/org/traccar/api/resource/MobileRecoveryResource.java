package org.traccar.api.resource;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.BaseResource;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.database.DeviceLookupService;
import org.traccar.mobile.FcmRecoveryService;
import org.traccar.mobile.FcmTokenStore;
import org.traccar.model.Device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;

/**
 * F2: endpoints móviles del recovery FCM (misma autenticación que el canal
 * HTTP de posiciones: X-Api-Key + X-Device-Id; NO se inventa otra identidad).
 *
 * - POST /api/mobile/v1/fcm-token     → registra/rota el token FCM del device.
 * - POST /api/mobile/v1/recovery-ack  → etapas de recuperación confirmadas por
 *   Android (RECEIVED/STARTED/FGS_ACTIVE/TRACKING_ACTIVE/GPS_CONFIRMED) con
 *   recoveryAttemptId; valida orden e idempotencia.
 */
@Path("mobile/v1")
@Produces(MediaType.APPLICATION_JSON)
public class MobileRecoveryResource extends BaseResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(MobileRecoveryResource.class);

    private final Config config;
    private final ObjectMapper mapper;
    private final DeviceLookupService devices;
    private final FcmTokenStore tokens;
    private final FcmRecoveryService recovery;

    @Inject
    public MobileRecoveryResource(Config config, ObjectMapper mapper,
            DeviceLookupService devices, FcmTokenStore tokens, FcmRecoveryService recovery) {
        this.config = config;
        this.mapper = mapper;
        this.devices = devices;
        this.tokens = tokens;
        this.recovery = recovery;
    }

    private Response authenticate(String apiKey, String deviceId) {
        // S1: clave actual + anterior (ventana de rotación de flota).
        if (!org.traccar.mobile.MobileApiKeyValidator.isValid(
                apiKey,
                config.getString(Keys.MOBILE_HTTP_API_KEY),
                config.getString(Keys.MOBILE_HTTP_API_KEY_PREVIOUS))) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        if (deviceId == null || deviceId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        Device device = devices.lookup(new String[] {deviceId});
        if (device == null) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        return null;
    }

    @PermitAll
    @POST
    @Path("fcm-token")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response registerToken(
            @HeaderParam("X-Api-Key") String apiKey,
            @HeaderParam("X-Device-Id") String deviceId,
            String body) {
        Response authError = authenticate(apiKey, deviceId);
        if (authError != null) {
            return authError;
        }
        try {
            JsonNode root = mapper.readTree(body);
            String token = root.path("fcmToken").asText(null);
            if (token == null || token.isBlank() || token.length() > 512) {
                return Response.status(Response.Status.BAD_REQUEST).build();
            }
            Device device = devices.lookup(new String[] {deviceId});
            tokens.register(device.getId(), token);
            // Diagnóstico sin exponer el token: solo prefijo hash + metadata.
            device.getAttributes().put("mobile.fcmTokenRegistered", true);
            device.getAttributes().put("mobile.fcmTokenPrefix", FcmTokenStore.tokenPrefix(token));
            device.getAttributes().put("mobile.fcmUpdatedAt", System.currentTimeMillis());
            if (root.hasNonNull("appVersion")) {
                device.getAttributes().put("mobile.appVersion", root.get("appVersion").asText());
            }
            storage.updateObject(device, new org.traccar.storage.query.Request(
                    new org.traccar.storage.query.Columns.Include("attributes"),
                    new org.traccar.storage.query.Condition.Equals("id", device.getId())));
            LOGGER.info("FCM token registrado para device {} (prefix={})",
                    device.getId(), FcmTokenStore.tokenPrefix(token));
            return Response.ok(new Ack(true, "registered")).build();
        } catch (Exception error) {
            LOGGER.warn("FCM token register failed", error);
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
    }

    @PermitAll
    @POST
    @Path("recovery-ack")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response recoveryAck(
            @HeaderParam("X-Api-Key") String apiKey,
            @HeaderParam("X-Device-Id") String deviceId,
            String body) {
        Response authError = authenticate(apiKey, deviceId);
        if (authError != null) {
            return authError;
        }
        try {
            JsonNode root = mapper.readTree(body);
            String attemptId = root.path("recoveryAttemptId").asText(null);
            String stage = root.path("stage").asText(null);
            String priority = root.path("priority").asText("");
            String reason = root.path("reason").asText("");
            if (attemptId == null || attemptId.isBlank() || stage == null || stage.isBlank()) {
                return Response.status(Response.Status.BAD_REQUEST).build();
            }
            Device device = devices.lookup(new String[] {deviceId});
            boolean accepted = recovery.acknowledge(device.getId(), attemptId, stage, priority, reason);
            return Response.ok(new Ack(accepted, accepted ? "accepted" : "rejected")).build();
        } catch (Exception error) {
            LOGGER.warn("FCM recovery ack failed", error);
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
    }

    public record Ack(boolean success, String status) {}
}
