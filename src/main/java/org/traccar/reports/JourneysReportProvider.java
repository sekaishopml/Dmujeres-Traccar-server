package org.traccar.reports;

import org.traccar.model.Event;
import org.traccar.model.Position;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * R9: lógica PURA del Reporte de Jornadas (auditoría CCTV).
 *
 * Empareja eventos mobileJourneyStarted/Ended en jornadas por equipo (una
 * jornada abierta sin END sigue activa; un START nuevo cierra la anterior de
 * facto) y calcula por jornada: puntos, distancia (haversine entre fixes
 * consecutivos), duración, máximo hueco y huecos >60 s — la métrica de la
 * cobertura ≥90 % del tiempo en movimiento.
 *
 * Sin dependencias de framework: testeable en JUnit puro.
 */
public final class JourneysReportProvider {

    public static final String TYPE_JOURNEY_STARTED = "mobileJourneyStarted";
    public static final String TYPE_JOURNEY_ENDED = "mobileJourneyEnded";

    /** Hueco que cuenta como "sin cobertura" en el reporte (segundos). */
    public static final long GAP_ALERT_SECONDS = 60L;

    /** Velocidad (nudos) a partir de la cual un tramo cuenta como "en marcha". */
    public static final double MOVING_SPEED_KNOTS = 3.0;

    /** Una jornada para el reporte (end == null → activa/en curso). */
    public static final class JourneyRow {
        public final long deviceId;
        public final long journeyId;
        public final Date start;
        public Date end;
        public long points;
        public double distanceM;
        public long maxGapSeconds;
        public long gapsOver60;

        JourneyRow(long deviceId, long journeyId, Date start, Date end) {
            this.deviceId = deviceId;
            this.journeyId = journeyId;
            this.start = start;
            this.end = end;
        }

        public long durationMs(Date now) {
            long endMs = end != null ? end.getTime() : now.getTime();
            return Math.max(0L, endMs - start.getTime());
        }

        public boolean active() {
            return end == null;
        }
    }

    /** Empareja los eventos (ordenados por tiempo) en jornadas por equipo. */
    public static List<JourneyRow> pairJourneys(List<Event> events) {
        List<JourneyRow> result = new ArrayList<>();
        Map<Long, JourneyRow> open = new HashMap<>();
        List<Event> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparing(Event::getEventTime));
        for (Event event : sorted) {
            long deviceId = event.getDeviceId();
            if (TYPE_JOURNEY_STARTED.equals(event.getType())) {
                JourneyRow openRow = open.get(deviceId);
                if (openRow != null) {
                    // Arranque nuevo sin END previo: la anterior se cierra de facto.
                    openRow.end = event.getEventTime();
                    result.add(openRow);
                    open.remove(deviceId);
                }
                open.put(deviceId, new JourneyRow(
                        deviceId, journeyIdOf(event), event.getEventTime(), null));
            } else if (TYPE_JOURNEY_ENDED.equals(event.getType())) {
                JourneyRow row = open.remove(deviceId);
                if (row != null) {
                    row.end = event.getEventTime();
                    result.add(row);
                }
            }
        }
        result.addAll(open.values());
        result.sort(Comparator.comparing((JourneyRow row) -> row.start));
        return result;
    }

    /** journeyId del atributo (la app lo manda); fallback = epoch del evento. */
    public static long journeyIdOf(Event event) {
        Object value = event.getAttributes() != null
                ? event.getAttributes().get("journeyId") : null;
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception notNumeric) {
            return event.getEventTime().getTime();
        }
    }

    /** Agrega estadísticas de captura a la jornada con sus posiciones. */
    public static void computeStats(JourneyRow row, List<Position> positions) {
        positions.sort(Comparator.comparing(Position::getFixTime));
        row.points = positions.size();
        double distance = 0;
        long maxGapSeconds = 0;
        long gapsOver60 = 0;
        Position previous = null;
        for (Position position : positions) {
            if (previous != null) {
                long dtSeconds = Math.max(0L, (position.getFixTime().getTime()
                        - previous.getFixTime().getTime()) / 1000L);
                boolean moving = previous.getSpeed() > MOVING_SPEED_KNOTS
                        || position.getSpeed() > MOVING_SPEED_KNOTS;
                // El máximo solo cuenta en marcha: una parada larga (estacionado)
                // no es un hueco de ruta, es una parada.
                if (moving && dtSeconds > maxGapSeconds) {
                    maxGapSeconds = dtSeconds;
                }
                if (moving && dtSeconds > GAP_ALERT_SECONDS) {
                    gapsOver60 += 1;
                }
                distance += haversine(previous.getLatitude(), previous.getLongitude(),
                        position.getLatitude(), position.getLongitude());
            }
            previous = position;
        }
        row.distanceM = distance;
        row.maxGapSeconds = maxGapSeconds;
        row.gapsOver60 = gapsOver60;
    }

    static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371_000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
