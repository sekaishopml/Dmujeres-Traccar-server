package org.traccar.handler;

import org.junit.jupiter.api.Test;
import org.traccar.model.Position;
import org.traccar.session.state.MotionStateV2Engine;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

public class MotionStateV2HandlerTest {

    private MotionStateV2Handler handler() {
        return new MotionStateV2Handler(mock(org.traccar.session.cache.CacheManager.class));
    }

    private Position position(long fixMs, double lat, double lon, double speedKn, String quality) {
        Position position = new Position("dmj-mqtt");
        position.setDeviceId(1);
        position.setFixTime(new Date(fixMs));
        position.setLatitude(lat);
        position.setLongitude(lon);
        position.setSpeed(speedKn);
        position.set("qualityClass", quality);
        position.setValid(true);
        return position;
    }

    @Test
    public void testSetsV2StateOnMobilePositions() {
        MotionStateV2Handler handler = handler();
        long base = 1_700_000_000_000L;
        Position first = position(base, -33.45, -70.66, 10, "GOOD");
        handler.onPosition(first, p -> {});
        // primer fix en movimiento → MOVING
        assertEquals("MOVING", first.getString(MotionStateV2Handler.KEY_MOTION_STATE_V2));
    }

    @Test
    public void testIgnoresLegacyProtocols() {
        MotionStateV2Handler handler = handler();
        Position legacy = new Position("osmand");
        legacy.setDeviceId(2);
        legacy.setFixTime(new Date(1_700_000_000_000L));
        legacy.setLatitude(-33.45);
        legacy.setLongitude(-70.66);
        legacy.setSpeed(10);
        legacy.setValid(true);
        handler.onPosition(legacy, p -> {});
        assertNull(legacy.getString(MotionStateV2Handler.KEY_MOTION_STATE_V2));
    }

    @Test
    public void testQualityInvalidMapsToUnknownState() {
        MotionStateV2Handler handler = handler();
        Position position = position(1_700_000_000_000L, -33.45, -70.66, 10, "POOR");
        MotionStateV2Engine.Observation obs = MotionStateV2Handler.toObservation(position);
        assertEquals(MotionStateV2Engine.QualityClass.POOR, obs.getQuality());
    }

    @Test
    public void testObservationConvertsKnotsToMps() {
        MotionStateV2Handler handler = handler();
        Position position = position(1_700_000_000_000L, -33.45, -70.66, 10, "GOOD");
        MotionStateV2Engine.Observation obs = MotionStateV2Handler.toObservation(position);
        assertEquals(5.14444, obs.getSpeedMps(), 0.001);
        assertEquals(1_700_000_000_000L, obs.getTimeMs());
        assertEquals(true, obs.isValid());
    }

}
