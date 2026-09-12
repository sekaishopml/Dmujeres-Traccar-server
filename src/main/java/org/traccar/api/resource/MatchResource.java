/*
 * DMujeres-Tracking: endpoint proxy de map-matching.
 *
 * GET /api/positions/match?deviceId=&from=&to[&accuracy=]
 * Lee las posiciones del rango (igual que el reporte), las manda en trozos de
 * 300 al servicio local GraphHopper (127.0.0.1:8991/match) y devuelve la
 * polilínea pegada a la red vial para dibujar en el dashboard.
 * Si el matcher falla o está caído, devuelve {"matched":null,...} y el
 * dashboard usa el trazo honesto (crudo) como fallback silencioso.
 */
package org.traccar.api.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.traccar.api.BaseResource;
import org.traccar.helper.model.PositionUtil;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Path("positions/match")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MatchResource extends BaseResource {

    private static final String MATCH_URL = "http://127.0.0.1:8991/match";
    private static final int CHUNK_SIZE = 300;
    private static final int MAX_REQUEST_POINTS = 2000;
    private static final int OVERLAP = 4;
    // Decimación de entrada: en una parada hay miles de fixes casi idénticos
    // que ahogan al Viterbi; se deja 1 punto por cada 12 m o 120 s (marcha
    // a 10 s conserva TODO: pata típica 30-150 m).
    private static final double MIN_LEG_METERS = 12;
    private static final long MIN_LEG_MS = 120_000;
    // Cache compartida (el resource se instancia por request): repetir la
    // misma repetición no vuelve a casar. 10 min TTL, tope 150 entradas.
    private static final long CACHE_TTL_MS = 10 * 60 * 1000;
    private static final int CACHE_MAX = 150;
    private static final java.util.concurrent.ConcurrentHashMap<String, CachedMatch> MATCH_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static class CachedMatch {
        final ObjectNode payload;
        final long storedAt;
        CachedMatch(ObjectNode payload, long storedAt) {
            this.payload = payload;
            this.storedAt = storedAt;
        }
    }

    private static ObjectNode cachedGet(String key) {
        CachedMatch entry = MATCH_CACHE.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() - entry.storedAt > CACHE_TTL_MS) {
            MATCH_CACHE.remove(key);
            return null;
        }
        return entry.payload;
    }

    private static void cachedPut(String key, ObjectNode payload) {
        if (MATCH_CACHE.size() >= CACHE_MAX) {
            MATCH_CACHE.clear();
        }
        MATCH_CACHE.put(key, new CachedMatch(payload, System.currentTimeMillis()));
    }

    @Inject
    private ObjectMapper mapper;

    /**
     * Match de tracks ya segmentados por el cliente (huecos temporales fuera):
     * { "tracks": [ [[lon,lat],...], ... ], "accuracy": 25 }.
     * Cada track se procesa independiente (trozos de 300 con solape) y se
     * devuelve en el mismo orden: { "segments": [ [[lon,lat],...], ... ],
     * "distance": m, "skipped": n } donde un segmento null indica que ese
     * track no casó (el cliente dibuja ahí el trazo honesto).
     */
    @POST
    public Response matchTracks(JsonNode body) {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode segments = mapper.createArrayNode();
        double totalDistance = 0;
        int skipped = 0;
        try {
            permissionsService.checkPermission(Device.class, getUserId(), body.path("deviceId").asLong(0));
            double accuracy = body.path("accuracy").asDouble(30.0);
            String cacheKey = "P:" + body.path("deviceId").asLong(0) + ":" + accuracy
                    + ":" + body.path("tracks").toString().hashCode();
            ObjectNode cached = cachedGet(cacheKey);
            if (cached != null) {
                return Response.ok(cached).build();
            }
            JsonNode tracks = body.path("tracks");
            if (!tracks.isArray()) {
                response.putNull("segments");
                response.put("error", "tracks_required");
                return Response.ok(response).build();
            }
            for (JsonNode track : tracks) {
                List<double[]> points = new ArrayList<>();
                for (JsonNode coord : track) {
                    if (coord.isArray() && coord.size() >= 2
                            && Double.isFinite(coord.get(0).asDouble())
                            && Double.isFinite(coord.get(1).asDouble())) {
                        points.add(new double[]{coord.get(0).asDouble(), coord.get(1).asDouble()});
                    }
                }
                if (points.size() < 2) {
                    segments.addNull();
                    skipped += 1;
                    continue;
                }
                MatchedTrack matched = matchPoints(points, accuracy);
                if (matched == null || matched.coords.isEmpty()) {
                    segments.addNull();
                    skipped += 1;
                    continue;
                }
                ArrayNode segment = mapper.createArrayNode();
                double previousLon = Double.NaN;
                double previousLat = Double.NaN;
                for (double[] coord : matched.coords) {
                    ArrayNode point = mapper.createArrayNode();
                    point.add(coord[0]);
                    point.add(coord[1]);
                    segment.add(point);
                    // Distancia HONESTA de lo dibujado (la suma de matchLength
                    // de los trozos solapa uniones y giros one-way: inflaba 20x).
                    if (Double.isFinite(previousLon)) {
                        totalDistance += haversineMeters(previousLat, previousLon, coord[1], coord[0]);
                    }
                    previousLon = coord[0];
                    previousLat = coord[1];
                }
                segments.add(segment);
            }
            response.set("segments", segments);
            response.put("distance", totalDistance);
            response.put("skipped", skipped);
            cachedPut(cacheKey, response);
            return Response.ok(response).build();
        } catch (Exception e) {
            response.putNull("segments");
            response.put("error", "matcher_unavailable");
            return Response.ok(response).build();
        }
    }

    private static class MatchedTrack {
        final List<double[]> coords = new ArrayList<>();
        double distance = 0;
    }

    @GET
    public Response match(
            @QueryParam("deviceId") long deviceId,
            @QueryParam("from") Date from, @QueryParam("to") Date to,
            @QueryParam("accuracy") Double accuracyOpt) throws StorageException {
        permissionsService.checkPermission(Device.class, getUserId(), deviceId);

        double accuracy = accuracyOpt != null && accuracyOpt > 0 ? accuracyOpt : 30.0;
        String cacheKey = "G:" + deviceId + ":"
                + (from != null ? from.getTime() : 0) + ":"
                + (to != null ? to.getTime() : 0) + ":" + accuracy;
        ObjectNode cached = cachedGet(cacheKey);
        if (cached != null) {
            return Response.ok(cached).build();
        }

        Stream<Position> stream = (from != null && to != null)
                ? PositionUtil.getPositionsStream(storage, deviceId, from, to)
                : storage.getObjectsStream(Position.class, new Request(
                        new Columns.All(), new Condition.LatestPositions(deviceId)));
        List<Position> positions = stream
                .filter(p -> Double.isFinite(p.getLatitude()) && Double.isFinite(p.getLongitude()))
                .limit(MAX_REQUEST_POINTS + 1)
                .collect(Collectors.toList());

        if (positions.size() < 2) {
            ObjectNode empty = mapper.createObjectNode();
            empty.putNull("matched");
            empty.put("raw", positions.size());
            empty.put("reason", "too_few");
            return Response.ok(empty).build();
        }

        positions = decimateForMatch(positions);
        List<double[]> points = new ArrayList<>();
        for (Position position : positions) {
            points.add(new double[]{position.getLongitude(), position.getLatitude()});
        }
        MatchedTrack matchedTrack = matchPoints(points, accuracy);
        ObjectNode response = mapper.createObjectNode();
        if (matchedTrack == null) {
            ObjectNode fallback = mapper.createObjectNode();
            fallback.putNull("matched");
            fallback.put("raw", positions.size());
            fallback.put("error", "matcher_unavailable");
            return Response.ok(fallback).build();
        }
        ArrayNode matched = mapper.createArrayNode();
        double honestDistance = 0;
        double previousLon = Double.NaN;
        double previousLat = Double.NaN;
        for (double[] coord : matchedTrack.coords) {
            ArrayNode point = mapper.createArrayNode();
            point.add(coord[0]);
            point.add(coord[1]);
            matched.add(point);
            if (Double.isFinite(previousLon)) {
                honestDistance += haversineMeters(previousLat, previousLon, coord[1], coord[0]);
            }
            previousLon = coord[0];
            previousLat = coord[1];
        }
        response.set("matched", matched);
        response.put("distance", honestDistance);
        response.put("raw", positions.size());
        response.put("accuracy", accuracy);
        cachedPut(cacheKey, response);
        return Response.ok(response).build();
    }

    /**
     * Casa una lista [lon,lat] en trozos de CHUNK_SIZE con solape OVERLAP:
     * devuelve null si el matcher está caído. Los puntos del solape se
     * deduplican al unir.
     */
    private MatchedTrack matchPoints(List<double[]> points, double accuracy) {
        MatchedTrack track = new MatchedTrack();
        for (int start = 0; start < points.size(); start += CHUNK_SIZE) {
            int fromIdx = start == 0 ? 0 : start - OVERLAP;
            List<double[]> chunk = points.subList(fromIdx, Math.min(start + CHUNK_SIZE, points.size()));
            JsonNode result = postChunk(chunk, accuracy);
            if (result == null) {
                return null;
            }
            track.distance += result.path("distance").asDouble(0);
            boolean first = true;
            for (JsonNode coord : result.path("matched")) {
                // Quita el primer punto de los trozos solapados para no duplicar la unión.
                if (start > 0 && first) {
                    first = false;
                    continue;
                }
                track.coords.add(new double[]{coord.get(0).asDouble(), coord.get(1).asDouble()});
            }
        }
        return track;
    }

    /**
     * Diezma fixes casi idénticos (paradas) sin tocar la marcha: conserva un
     * punto si dista >= MIN_LEG_METERS del anterior conservado O han pasado
     * >= MIN_LEG_MS. Primero y último siempre. Testeable aisladamente.
     */
    static List<Position> decimateForMatch(List<Position> input) {
        if (input.size() <= 2) {
            return input;
        }
        List<Position> kept = new ArrayList<>();
        kept.add(input.get(0));
        for (int i = 1; i < input.size() - 1; i++) {
            Position prev = kept.get(kept.size() - 1);
            Position current = input.get(i);
            double dist = haversineMeters(prev.getLatitude(), prev.getLongitude(),
                    current.getLatitude(), current.getLongitude());
            long dt = Math.abs(current.getFixTime().getTime() - prev.getFixTime().getTime());
            if (dist >= MIN_LEG_METERS || dt >= MIN_LEG_MS) {
                kept.add(current);
            }
        }
        kept.add(input.get(input.size() - 1));
        return kept;
    }

    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadius = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                        * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * earthRadius * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    private JsonNode postChunk(List<double[]> chunk, double accuracy) {
        try {
            ObjectNode request = mapper.createObjectNode();
            ArrayNode points = mapper.createArrayNode();
            for (double[] position : chunk) {
                ArrayNode point = mapper.createArrayNode();
                point.add(position[0]);
                point.add(position[1]);
                points.add(point);
            }
            request.set("points", points);
            request.put("accuracy", accuracy);
            byte[] body = mapper.writeValueAsBytes(request);

            HttpURLConnection connection = (HttpURLConnection) URI.create(MATCH_URL).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(120000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int code = connection.getResponseCode();
            if (code != 200) {
                return null;
            }
            try (InputStream input = connection.getInputStream()) {
                JsonNode parsed = mapper.readTree(input);
                if (parsed == null || !parsed.has("matched")) {
                    return null;
                }
                return parsed;
            }
        } catch (IOException e) {
            return null;
        }
    }
}