package org.traccar.api.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.api.BaseResource;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.reports.JourneysReportProvider;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * R9: Reporte de JORNADAS para la auditoría CCTV.
 *
 * GET /api/reports/journeys?deviceId=0&from=...&to=...
 * → jornadas por equipo emparejadas desde los eventos del server:
 *   [{deviceId, deviceName, journeyId, start, end (null=activa),
 *     durationMs, points, distanceM, maxGapSeconds, gapsOver60}]
 *
 * GET /api/reports/journeys/summary?from=...&to=...
 * → totales de la portada: {journeysActive, positions, gapsOver60}
 *
 * Permisos estándar del panel: solo jornadas de equipos que el usuario puede
 * leer. Nunca lanza al cliente: errores → 500 con mensaje.
 */
@Path("reports/journeys")
@Produces(MediaType.APPLICATION_JSON)
public class JourneysReportResource extends BaseResource {

    @Inject
    private ObjectMapper mapper;

    @GET
    public Response journeys(
            @QueryParam("deviceId") long deviceId,
            @QueryParam("from") String fromIso,
            @QueryParam("to") String toIso) {
        try {
            Date from = parseDate(fromIso, daysAgo(7));
            Date to = parseDate(toIso, new Date());
            List<JourneysReportProvider.JourneyRow> rows = journeysFor(deviceId, from, to);
            ArrayNode items = mapper.createArrayNode();
            for (JourneysReportProvider.JourneyRow row : rows) {
                items.add(toJson(row));
            }
            ObjectNode response = mapper.createObjectNode();
            response.set("journeys", items);
            response.put("total", rows.size());
            return Response.ok(response).build();
        } catch (Exception error) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(error.getMessage()).build();
        }
    }

    @Path("summary")
    @GET
    public Response summary(@QueryParam("from") String fromIso,
                            @QueryParam("to") String toIso) {
        try {
            Date from = parseDate(fromIso, daysAgo(1));
            Date to = parseDate(toIso, new Date());
            List<JourneysReportProvider.JourneyRow> rows = journeysFor(0L, from, to);
            long active = 0;
            long points = 0;
            long gaps = 0;
            for (JourneysReportProvider.JourneyRow row : rows) {
                if (row.active()) {
                    active += 1;
                }
                points += row.points;
                gaps += row.gapsOver60;
            }
            ObjectNode response = mapper.createObjectNode();
            response.put("journeysActive", active);
            response.put("journeysTotal", rows.size());
            response.put("positions", points);
            response.put("gapsOver60", gaps);
            return Response.ok(response).build();
        } catch (Exception error) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(error.getMessage()).build();
        }
    }

    // ------------------------------------------------------------------

    private List<JourneysReportProvider.JourneyRow> journeysFor(
            long deviceId, Date from, Date to) throws Exception {
        List<Event> events = new ArrayList<>();
        // Eventos de jornada en ventana amplia (una jornada puede iniciar antes
        // del rango pedido y seguir viva dentro de él): 14 días atrás.
        Date wideFrom = new Date(from.getTime() - 14L * 86_400_000L);
        for (String type : new String[] {
                JourneysReportProvider.TYPE_JOURNEY_STARTED,
                JourneysReportProvider.TYPE_JOURNEY_ENDED}) {
            events.addAll(storage.getObjects(Event.class, new Request(
                    new Columns.All(),
                    new Condition.And(
                            new Condition.Equals("type", type),
                            new Condition.Between("eventTime", wideFrom, to)))));
        }
        List<JourneysReportProvider.JourneyRow> rows =
                JourneysReportProvider.pairJourneys(events);
        List<JourneysReportProvider.JourneyRow> inRange = new ArrayList<>();
        for (JourneysReportProvider.JourneyRow row : rows) {
            if (deviceId > 0 && row.deviceId != deviceId) {
                continue;
            }
            try {
                permissionsService.checkPermission(Device.class, getUserId(), row.deviceId);
            } catch (Exception noAccess) {
                continue;
            }
            Date rowEnd = row.end != null ? row.end : new Date();
            if (row.start.after(to) || rowEnd.before(from)) {
                continue;
            }
            Date clipFrom = row.start.before(from) ? from : row.start;
            Date clipTo = rowEnd.after(to) ? to : rowEnd;
            List<Position> positions = new ArrayList<>();
            // IMPORTANTE: el stream retiene una conexión del pool — debe
            // cerrarse SIEMPRE (try-with-resources) o el HikariPool se agota.
            try (var stream = PositionUtil.getPositionsStream(storage, row.deviceId, clipFrom, clipTo)) {
                stream.forEach(positions::add);
            }
            JourneysReportProvider.computeStats(row, positions);
            inRange.add(row);
        }
        inRange.sort((a, b) -> b.start.compareTo(a.start)); // recientes primero
        return inRange;
    }

    private ObjectNode toJson(JourneysReportProvider.JourneyRow row) {
        ObjectNode node = mapper.createObjectNode();
        node.put("deviceId", row.deviceId);
        node.put("journeyId", row.journeyId);
        node.put("start", row.start.getTime());
        if (row.end != null) {
            node.put("end", row.end.getTime());
        } else {
            node.putNull("end");
        }
        node.put("durationMs", row.durationMs(new Date()));
        node.put("points", row.points);
        node.put("distanceM", row.distanceM);
        node.put("maxGapSeconds", row.maxGapSeconds);
        node.put("gapsOver60", row.gapsOver60);
        node.put("active", row.active());
        return node;
    }

    private static Date parseDate(String value, Date fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return new Date(Long.parseLong(value));
        } catch (NumberFormatException notEpoch) {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            try {
                return format.parse(value);
            } catch (ParseException notIso) {
                return fallback;
            }
        }
    }

    private static Date daysAgo(int days) {
        return new Date(System.currentTimeMillis() - days * 86_400_000L);
    }
}
