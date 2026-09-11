package org.traccar.api.resource;

import org.junit.jupiter.api.Test;
import org.traccar.model.Position;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MatchResourceTest {

    private static Position position(double latitude, double longitude, long timeMs) {
        Position position = new Position();
        position.setDeviceId(1);
        position.setLatitude(latitude);
        position.setLongitude(longitude);
        position.setFixTime(new Date(timeMs));
        return position;
    }

    @Test
    public void testDecimateKeepsDrivingPoints() {
        // Marcha a 10 s, patas de 110 m: conserva todo + primero/último.
        List<Position> input = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            input.add(position(-2.20 + i * 0.001, -79.88, i * 10_000L));
        }
        List<Position> kept = MatchResource.decimateForMatch(input);
        assertEquals(20, kept.size());
    }

    @Test
    public void testDecimateCollapsesStops() {
        // 500 fixes en el mismo sitio cada 10 s (83 min parada): colapsa a ~42
        // (1 cada 120 s) + conserva siempre primero y último.
        List<Position> input = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            input.add(position(-2.22741 + (i % 3) * 0.00001, -79.88845 + (i % 5) * 0.00001, i * 10_000L));
        }
        List<Position> kept = MatchResource.decimateForMatch(input);
        assertTrue(kept.size() < 60, "esperado ~42, real=" + kept.size());
        assertEquals(input.get(0).getFixTime(), kept.get(0).getFixTime());
        assertEquals(input.get(499).getFixTime(), kept.get(kept.size() - 1).getFixTime());
    }

    @Test
    public void testDecimateTinyInput() {
        assertEquals(0, MatchResource.decimateForMatch(new ArrayList<>()).size());
        List<Position> one = List.of(position(-2.2, -79.88, 0));
        assertEquals(1, MatchResource.decimateForMatch(one).size());
    }
}