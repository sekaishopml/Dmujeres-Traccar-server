package org.traccar.reports;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.traccar.model.Event;
import org.traccar.model.Position;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;

/** R9: emparejamiento de jornadas y estadísticas del reporte. */
class JourneysReportProviderTest {

    private static Calendar at(int year, int month, int day, int hour, int minute) {
        Calendar calendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        calendar.set(year, month - 1, day, hour, minute, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar;
    }

    private static Event journeyEvent(String type, long deviceId, Calendar when) {
        Event event = new Event(type, deviceId);
        event.setEventTime(when.getTime());
        return event;
    }

    private static Position position(long deviceId, Calendar when,
                                     double lat, double lon) {
        Position position = new Position();
        position.setDeviceId(deviceId);
        position.setFixTime(when.getTime());
        position.setLatitude(lat);
        position.setLongitude(lon);
        position.setSpeed(10.0); // en marcha (nudos) para que el hueco cuente
        return position;
    }

    @Test
    void emparejaStartConEnd() {
        List<Event> events = new ArrayList<>();
        events.add(journeyEvent(JourneysReportProvider.TYPE_JOURNEY_STARTED, 2L,
                at(2026, 9, 20, 7, 0)));
        events.add(journeyEvent(JourneysReportProvider.TYPE_JOURNEY_ENDED, 2L,
                at(2026, 9, 20, 9, 0)));
        List<JourneysReportProvider.JourneyRow> rows =
                JourneysReportProvider.pairJourneys(events);
        Assertions.assertEquals(1, rows.size());
        Assertions.assertEquals(2L, rows.get(0).deviceId);
        Assertions.assertEquals(7_200_000L, rows.get(0).durationMs(new Date(0L)));
        Assertions.assertFalse(rows.get(0).active());
    }

    @Test
    void jornadaSinEndQuedaActiva() {
        List<Event> events = new ArrayList<>();
        events.add(journeyEvent(JourneysReportProvider.TYPE_JOURNEY_STARTED, 2L,
                at(2026, 9, 20, 7, 0)));
        List<JourneysReportProvider.JourneyRow> rows =
                JourneysReportProvider.pairJourneys(events);
        Assertions.assertEquals(1, rows.size());
        Assertions.assertTrue(rows.get(0).active());
        Assertions.assertTrue(rows.get(0).durationMs(new Date()) > 0L);
    }

    @Test
    void startDuplicadoCierraLaAnterior() {
        // Dos START sin END: dos jornadas (la primera cierra de facto en el
        // arranque de la segunda). Caso real: reinicio de app / jornada vieja.
        List<Event> events = new ArrayList<>();
        events.add(journeyEvent(JourneysReportProvider.TYPE_JOURNEY_STARTED, 2L,
                at(2026, 9, 20, 7, 0)));
        events.add(journeyEvent(JourneysReportProvider.TYPE_JOURNEY_STARTED, 2L,
                at(2026, 9, 21, 7, 0)));
        events.add(journeyEvent(JourneysReportProvider.TYPE_JOURNEY_ENDED, 2L,
                at(2026, 9, 21, 9, 0)));
        List<JourneysReportProvider.JourneyRow> rows =
                JourneysReportProvider.pairJourneys(events);
        Assertions.assertEquals(2, rows.size());
        // La primera cerró de facto cuando arrancó la segunda.
        Assertions.assertNotNull(rows.get(0).end,
                "la primera jornada debe cerrar al arrancar la segunda");
        Assertions.assertEquals(
                at(2026, 9, 21, 7, 0).getTimeInMillis(),
                rows.get(0).end.getTime());
        // La segunda es la que recibió el END.
        Assertions.assertFalse(rows.get(1).active());
    }

    @Test
    void separaJornadasPorEquipo() {
        List<Event> events = new ArrayList<>();
        events.add(journeyEvent(JourneysReportProvider.TYPE_JOURNEY_STARTED, 2L,
                at(2026, 9, 20, 7, 0)));
        events.add(journeyEvent(JourneysReportProvider.TYPE_JOURNEY_STARTED, 5L,
                at(2026, 9, 20, 8, 0)));
        List<JourneysReportProvider.JourneyRow> rows =
                JourneysReportProvider.pairJourneys(events);
        Assertions.assertEquals(2, rows.size());
        Assertions.assertEquals(2L, rows.get(0).deviceId);
        Assertions.assertEquals(5L, rows.get(1).deviceId);
    }

    @Test
    void statsCuentanPuntosDistanciaYGaps() {
        List<Event> events = new ArrayList<>();
        events.add(journeyEvent(JourneysReportProvider.TYPE_JOURNEY_STARTED, 2L,
                at(2026, 9, 20, 7, 0)));
        JourneysReportProvider.JourneyRow row =
                JourneysReportProvider.pairJourneys(events).get(0);

        // 0.001° lat ≈ 111 m; tres fixes a 60 s; luego un hueco de 120 s.
        List<Position> positions = new ArrayList<>();
        Calendar t = at(2026, 9, 20, 7, 0);
        positions.add(position(2L, t, 0.0, 0.0));
        Calendar t2 = at(2026, 9, 20, 7, 0);
        t2.set(Calendar.MILLISECOND, 0);
        t2.add(Calendar.SECOND, 15);
        positions.add(position(2L, t2, 0.001, 0.0));
        Calendar t3 = at(2026, 9, 20, 7, 0);
        t3.add(Calendar.SECOND, 30);
        positions.add(position(2L, t3, 0.002, 0.0));
        Calendar t4 = at(2026, 9, 20, 7, 0);
        t4.add(Calendar.SECOND, 135);
        positions.add(position(2L, t4, 0.003, 0.0));

        JourneysReportProvider.computeStats(row, positions);
        Assertions.assertEquals(4, row.points);
        // 3 patas de ~111 m c/u ≈ 333 m (tolerancia).
        Assertions.assertEquals(333.0, row.distanceM, 3.0);
        Assertions.assertEquals(105L, row.maxGapSeconds);
        Assertions.assertEquals(1L, row.gapsOver60);
    }
}
