package org.traccar.api.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.traccar.api.BaseResource;
import org.traccar.mobile.MobileTimelineService;
import org.traccar.mobile.MobileTimelineService.Entry;
import org.traccar.model.Device;

import java.util.List;

/**
 * P5 (§41): GET /api/devices/{deviceId}/timeline?from=&to=&limit=
 *
 * Autenticación estándar de Traccar (sesión del panel) + permiso sobre el
 * dispositivo, igual que {@link MobileContinuityResource}. {@code from}/{@code to}
 * aceptan epoch ms (o segundos) e ISO-8601; por defecto, últimas 24 h.
 * Solo lectura de evidencia: no escribe nada.
 */
@Path("devices/{deviceId}/timeline")
@Produces(MediaType.APPLICATION_JSON)
public class MobileTimelineResource extends BaseResource {

    private final MobileTimelineService timeline;

    @Inject
    public MobileTimelineResource(MobileTimelineService timeline) {
        this.timeline = timeline;
    }

    @GET
    public MobileTimelineService.Timeline get(
            @PathParam("deviceId") long deviceId,
            @QueryParam("from") String fromRaw,
            @QueryParam("to") String toRaw,
            @QueryParam("limit") Integer limitRaw) throws Exception {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);
        long now = System.currentTimeMillis();
        long to = MobileTimelineService.parseTime(toRaw, now);
        long from = MobileTimelineService.parseTime(fromRaw, to - MobileTimelineService.DEFAULT_WINDOW_MS);
        if (from > to) {
            long swap = from;
            from = to;
            to = swap;
        }
        int limit = MobileTimelineService.clampLimit(limitRaw);
        List<Entry> entries = timeline.collect(deviceId, from, to);
        boolean truncated = entries.size() > limit;
        List<Entry> page = truncated ? List.copyOf(entries.subList(0, limit)) : entries;
        return new MobileTimelineService.Timeline(
                deviceId, from, to, limit, page.size(), truncated, page);
    }
}
