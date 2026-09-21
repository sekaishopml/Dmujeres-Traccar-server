package org.traccar.api.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.traccar.api.BaseResource;
import org.traccar.mobile.MobileContinuityService;
import org.traccar.model.Device;

import java.util.List;

/**
 * FASE 5/7: continuidad de jornada para el dashboard DMujeres.
 * GET /api/devices/{deviceId}/continuity?journeyId=… | ?from=…&to=…&limit=…
 *
 * Autenticación estándar de Traccar (sesión del panel) + permiso sobre el
 * dispositivo. Fórmulas explícitas en la respuesta (denominadores incluidos).
 */
@Path("devices/{deviceId}/continuity")
@Produces(MediaType.APPLICATION_JSON)
public class MobileContinuityResource extends BaseResource {

    private final MobileContinuityService continuity;

    @Inject
    public MobileContinuityResource(MobileContinuityService continuity) {
        this.continuity = continuity;
    }

    @GET
    public List<MobileContinuityService.JourneyContinuity> get(
            @PathParam("deviceId") long deviceId,
            @QueryParam("journeyId") Long journeyId,
            @QueryParam("sessionId") String sessionId,
            @QueryParam("from") Long fromMs,
            @QueryParam("to") Long toMs,
            @QueryParam("limit") Integer limit) throws Exception {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        if (journeyId != null && journeyId > 0) {
            return List.of(continuity.byJourney(deviceId, journeyId));
        }
        if (sessionId != null && !sessionId.isBlank()) {
            return List.of(continuity.bySession(deviceId, sessionId.trim()));
        }
        long to = toMs != null && toMs > 0 ? toMs : System.currentTimeMillis();
        long from = fromMs != null && fromMs > 0 ? fromMs : to - 7L * 24 * 3_600_000L;
        return continuity.recent(deviceId, from, to, limit != null ? limit : 20);
    }
}
