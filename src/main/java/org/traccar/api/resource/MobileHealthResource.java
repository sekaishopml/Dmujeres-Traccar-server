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
import org.traccar.mobile.MobileHealthService;
import org.traccar.model.Device;

import java.util.Objects;

/**
 * FASE 7 (§21): POST /api/mobile/v1/health — snapshots de salud del cliente.
 *
 * Autenticación idéntica al canal HTTP móvil: {@code X-Api-Key} compartida
 * ({@code mobile.http.apiKey}) + {@code X-Device-Id} con el uniqueId (nunca el
 * id numérico). El dispositivo se resuelve SIEMPRE server-side.
 *
 * Respuestas: 200 con contadores honestos {accepted, duplicates, rejected,
 * throttled}; 400 JSON inválido; 413 cuerpo excesivo; 401 clave; 404 canal
 * apagado; 403 dispositivos desconocidos.
 */
@Path("mobile/v1/health")
@Produces(MediaType.APPLICATION_JSON)
public class MobileHealthResource extends BaseResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(MobileHealthResource.class);

    private final Config config;
    private final DeviceLookupService devices;
    private final MobileHealthService health;

    @Inject
    public MobileHealthResource(Config config, DeviceLookupService devices, MobileHealthService health) {
        this.config = config;
        this.devices = devices;
        this.health = health;
    }

    @PermitAll
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response submit(
            @HeaderParam("X-Api-Key") String apiKey,
            @HeaderParam("X-Device-Id") String deviceId,
            String body) {
        if (!config.getBoolean(Keys.MOBILE_HTTP_ENABLE)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
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
        Device device = devices.lookup(new String[] {deviceId.trim()});
        if (device == null) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        MobileHealthService.Outcome outcome = health.ingest(device, body);
        if (!outcome.throttled() && outcome.accepted() == 0 && outcome.duplicates() == 0
                && outcome.rejected() == 0) {
            // Payload vacío/ilegible: distinguir tamaño de formato.
            if (body != null && body.length() > MobileHealthService.MAX_BODY_BYTES) {
                return Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE).build();
            }
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        LOGGER.debug("Mobile health ingest: accepted={} duplicates={} rejected={} throttled={}",
                outcome.accepted(), outcome.duplicates(), outcome.rejected(), outcome.throttled());
        return Response.ok(outcome).build();
    }
}
