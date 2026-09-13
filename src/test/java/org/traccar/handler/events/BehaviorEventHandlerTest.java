package org.traccar.handler.events;

import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BehaviorEventHandlerTest {

    @Test
    public void testReplayOldPositionSkipsBrakingEvent() throws ParseException {
        CacheManager cacheManager = mock(CacheManager.class);
        Config config = new Config();
        config.setString(org.traccar.config.Keys.EVENT_BEHAVIOR_BRAKING_THRESHOLD, "2.0");
        BehaviorEventHandler handler = new BehaviorEventHandler(config, cacheManager);

        Position last = position("2017-01-01 00:10:00", 5.0);
        when(cacheManager.getPosition(7L)).thenReturn(last);

        // Replay antiguo: fixTime < cached y frenada drástica → sin dt negativo fabricado.
        Position replayed = position("2017-01-01 00:00:00", 50.0);
        List<Object> events = new ArrayList<>();
        handler.onPosition(replayed, events::add);

        assertTrue(events.isEmpty());
    }

    @Test
    public void testFreshPositionDetectsBraking() throws ParseException {
        CacheManager cacheManager = mock(CacheManager.class);
        Config config = new Config();
        config.setString(org.traccar.config.Keys.EVENT_BEHAVIOR_BRAKING_THRESHOLD, "2.0");
        BehaviorEventHandler handler = new BehaviorEventHandler(config, cacheManager);

        Position last = position("2017-01-01 00:00:00", 50.0);
        when(cacheManager.getPosition(7L)).thenReturn(last);

        // 50 kn → 0 kn en 10 s: ~25.7 m/s² de frenada, muy por encima de 2.0.
        Position fresh = position("2017-01-01 00:00:10", 0.0);
        List<Object> events = new ArrayList<>();
        handler.onPosition(fresh, events::add);

        assertTrue(!events.isEmpty());
    }

    private Position position(String time, double speed) throws ParseException {
        Position position = new Position();
        position.setDeviceId(7L);
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        position.setFixTime(dateFormat.parse(time));
        position.setTime(position.getFixTime());
        position.setSpeed(speed);
        return position;
    }

}
