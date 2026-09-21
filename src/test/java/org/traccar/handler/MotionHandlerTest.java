package org.traccar.handler;

import org.junit.jupiter.api.Test;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Device;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MotionHandlerTest {

    private MotionHandler handler() {
        var cacheManager = mock(CacheManager.class);
        when(cacheManager.getObject(eq(Device.class), anyLong())).thenReturn(mock(Device.class));
        var config = mock(Config.class);
        when(config.getString(Keys.EVENT_MOTION_SPEED_THRESHOLD.getKey())).thenReturn("0.01");
        when(cacheManager.getConfig()).thenReturn(config);
        return new MotionHandler(cacheManager);
    }

    @Test
    public void testCalculateMotion() {

        var cacheManager = mock(CacheManager.class);
        when(cacheManager.getObject(eq(Device.class), anyLong())).thenReturn(mock(Device.class));
        var config = mock(Config.class);
        when(config.getString(Keys.EVENT_MOTION_SPEED_THRESHOLD.getKey())).thenReturn("0.01");
        when(cacheManager.getConfig()).thenReturn(config);

        MotionHandler motionHandler = new MotionHandler(cacheManager);

        Position position = new Position();
        motionHandler.handlePosition(position, p -> {});

        assertEquals(false, position.getAttributes().get(Position.KEY_MOTION));

    }

    @Test
    public void testDopplerCeroSinEvidenciaNoEsMovimiento() {
        MotionHandler handler = handler();
        Position position = new Position();
        position.setSpeed(0);
        // sin speedSource, sin distancia: no se sabe → no moving (conservador
        // en KEY_MOTION; el estado V2 decide por su cuenta)
        handler.handlePosition(position, p -> {});
        assertEquals(false, position.getAttributes().get(Position.KEY_MOTION));
    }

    @Test
    public void testDopplerCeroConDistanciaEsMovimiento() {
        var cacheManager = mock(CacheManager.class);
        var config = mock(Config.class);
        when(config.getString(Keys.EVENT_MOTION_SPEED_THRESHOLD.getKey())).thenReturn("0.01");
        when(cacheManager.getConfig()).thenReturn(config);
        when(cacheManager.getObject(eq(Device.class), anyLong())).thenReturn(mock(Device.class));
        var last = new Position();
        last.setFixTime(new Date(System.currentTimeMillis() - 10_000));
        when(cacheManager.getPosition(anyLong())).thenReturn(last);
        MotionHandler qualityAware = new MotionHandler(cacheManager);
        Position position = new Position();
        position.setSpeed(0);
        position.set("speedSource", "implied");
        position.set(Position.KEY_DISTANCE, 120.0);
        position.setFixTime(new Date());
        qualityAware.handlePosition(position, p -> {});
        assertEquals(true, position.getAttributes().get(Position.KEY_MOTION));
    }

    @Test
    public void testDopplerFiableCeroEsParado() {
        MotionHandler handler = handler();
        Position position = new Position();
        position.setSpeed(0);
        position.set("speedSource", "doppler");
        position.set(Position.KEY_DISTANCE, 120.0);
        handler.handlePosition(position, p -> {});
        assertEquals(false, position.getAttributes().get(Position.KEY_MOTION));
    }

    @Test
    public void testDopplerMoviendoEsMovimiento() {
        MotionHandler handler = handler();
        Position position = new Position();
        position.setSpeed(10);
        position.set("speedSource", "doppler");
        handler.handlePosition(position, p -> {});
        assertEquals(true, position.getAttributes().get(Position.KEY_MOTION));
    }

}
