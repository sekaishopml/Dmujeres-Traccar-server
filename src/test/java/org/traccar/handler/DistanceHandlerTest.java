package org.traccar.handler;

import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DistanceHandlerTest {

    @Test
    public void testCalculateDistance() {

        DistanceHandler distanceHandler = new DistanceHandler(new Config(), mock(CacheManager.class));

        Position position = new Position();
        distanceHandler.handlePosition(position, p -> {});

        assertEquals(0.0, position.getAttributes().get(Position.KEY_DISTANCE));
        assertEquals(0.0, position.getAttributes().get(Position.KEY_TOTAL_DISTANCE));

        position.set(Position.KEY_DISTANCE, 100);

        distanceHandler.handlePosition(position, p -> {});

        assertEquals(100.0, position.getAttributes().get(Position.KEY_DISTANCE));
        assertEquals(100.0, position.getAttributes().get(Position.KEY_TOTAL_DISTANCE));

    }

    @Test
    public void testReplayAgainstLaterCachedPositionSkipsDistance() throws ParseException {
        CacheManager cacheManager = mock(CacheManager.class);
        DistanceHandler distanceHandler = new DistanceHandler(new Config(), cacheManager);

        Position last = position("2017-01-01 00:10:00", -33.45, -70.67);
        last.set(Position.KEY_TOTAL_DISTANCE, 5000.0);
        when(cacheManager.getPosition(7L)).thenReturn(last);

        Position replayed = position("2017-01-01 00:00:00", -33.40, -70.60);
        distanceHandler.handlePosition(replayed, p -> {});

        // El replay antiguo NO calcula ni escribe distance/totalDistance: quedan
        // los valores absurdos fuera de la fila (el replay honesto no los usa).
        assertFalse(replayed.hasAttribute(Position.KEY_DISTANCE));
        assertFalse(replayed.hasAttribute(Position.KEY_TOTAL_DISTANCE));
    }

    @Test
    public void testFreshPositionStillComputesDistance() throws ParseException {
        CacheManager cacheManager = mock(CacheManager.class);
        DistanceHandler distanceHandler = new DistanceHandler(new Config(), cacheManager);

        Position last = position("2017-01-01 00:00:00", -33.45, -70.67);
        last.set(Position.KEY_TOTAL_DISTANCE, 100.0);
        when(cacheManager.getPosition(7L)).thenReturn(last);

        Position fresh = position("2017-01-01 00:01:00", -33.40, -70.60);
        distanceHandler.handlePosition(fresh, p -> {});

        assertEquals(100.0, fresh.getDouble(Position.KEY_TOTAL_DISTANCE) - fresh.getDouble(Position.KEY_DISTANCE),
                0.001);
        assertTrue(fresh.getDouble(Position.KEY_DISTANCE) > 0.0);
        assertTrue(fresh.getDouble(Position.KEY_TOTAL_DISTANCE) > 100.0);
    }

    private Position position(String time, double latitude, double longitude) throws ParseException {
        Position position = new Position();
        position.setDeviceId(7L);
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        position.setFixTime(dateFormat.parse(time));
        position.setLatitude(latitude);
        position.setLongitude(longitude);
        return position;
    }

}
