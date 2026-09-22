/* Copyright 2026 Anton Tananaev (anton@traccar.org) */
package org.traccar.api.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.api.BaseResource;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.database.NotificationManager;
import org.traccar.mobile.MobileJourneyRegistry;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * INICIO/FIN de jornada de la app de respaldo (OsmAnd): mismas validaciones de
 * canal/llave/device que MobileConfigResource y mismo estado y eventos que el
 * canal nativo. Pruebas de unidad: se llama al método del recurso directamente,
 * con Storage/NotificationManager/Registry mockeados.
 */
public class MobileJourneyResourceTest {

    private static final String UNIQUE_ID = "device-123";
    private static final String API_KEY = "test-shared-key";
    private static final long DEVICE_ID = 42L;

    private Config config;
    private Device device;
    private Storage storage;
    private MobileJourneyRegistry registry;
    private NotificationManager notifications;
    private MobileJourneyResource resource;

    @BeforeEach
    public void setUp() throws Exception {
        config = mock(Config.class);
        when(config.getBoolean(Keys.MOBILE_HTTP_ENABLE)).thenReturn(true);
        when(config.getString(Keys.MOBILE_HTTP_API_KEY)).thenReturn(API_KEY);

        device = new Device();
        device.setId(DEVICE_ID);
        device.setUniqueId(UNIQUE_ID);

        storage = mock(Storage.class);
        when(storage.getObject(any(), any(Request.class))).thenReturn(device);

        registry = mock(MobileJourneyRegistry.class);
        notifications = mock(NotificationManager.class);

        resource = new MobileJourneyResource(config, new ObjectMapper(), registry, notifications);
        Field field = BaseResource.class.getDeclaredField("storage");
        field.setAccessible(true);
        field.set(resource, storage);
    }

    private Response post(String apiKey, String body) throws Exception {
        return resource.submit(apiKey, body);
    }

    private static String startBody(long journeyId) {
        return "{\"deviceId\":\"" + UNIQUE_ID + "\",\"action\":\"start\","
                + "\"journeyId\":" + journeyId + ",\"client\":\"dmujeres-traccar\"}";
    }

    @Test
    public void testChannelDisabledIsNotFound() throws Exception {
        when(config.getBoolean(Keys.MOBILE_HTTP_ENABLE)).thenReturn(false);
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), post(API_KEY, startBody(1000L)).getStatus());
    }

    @Test
    public void testWrongApiKeyIsUnauthorized() throws Exception {
        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), post("wrong-key", startBody(1000L)).getStatus());
    }

    @Test
    public void testMissingDeviceIdIsBadRequest() throws Exception {
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(),
                post(API_KEY, "{\"action\":\"start\"}").getStatus());
    }

    @Test
    public void testUnknownDeviceIsNotFound() throws Exception {
        when(storage.getObject(any(), any(Request.class))).thenReturn(null);
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), post(API_KEY, startBody(1000L)).getStatus());
    }

    @Test
    public void testUnknownActionIsBadRequest() throws Exception {
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(),
                post(API_KEY, "{\"deviceId\":\"" + UNIQUE_ID + "\",\"action\":\"pause\"}").getStatus());
    }

    @Test
    public void testBrokenJsonIsBadRequest() throws Exception {
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), post(API_KEY, "not-json").getStatus());
    }

    @Test
    public void testStartJourneyPersistsStateAndEvent() throws Exception {
        Response response = post(API_KEY, startBody(1_700_000_000_000L));
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(Map.of("ok", true), response.getEntity());

        assertEquals(1_700_000_000_000L, device.getAttributes().get(MobileJourneyResource.ATTR_JOURNEY_ID));
        assertEquals("dmujeres-traccar", device.getAttributes().get(MobileJourneyResource.ATTR_CLIENT));
        verify(registry).start(DEVICE_ID, 1_700_000_000_000L);
        verify(storage).updateObject(eq(device), any(Request.class));

        Event event = capturedEvent();
        assertEquals(Event.TYPE_MOBILE_JOURNEY_STARTED, event.getType());
        assertEquals(DEVICE_ID, event.getDeviceId());
        assertEquals("info", event.getAttributes().get("mobileSeverity"));
        assertEquals(1_700_000_000_000L, event.getAttributes().get("journeyId"));
    }

    @Test
    public void testStartWithoutJourneyIdUsesNow() throws Exception {
        long before = System.currentTimeMillis();
        assertEquals(Response.Status.OK.getStatusCode(),
                post(API_KEY, "{\"deviceId\":\"" + UNIQUE_ID + "\",\"action\":\"start\"}").getStatus());
        Object stored = device.getAttributes().get(MobileJourneyResource.ATTR_JOURNEY_ID);
        assertTrue(stored instanceof Long && (Long) stored >= before && (Long) stored <= System.currentTimeMillis());
        verify(registry).start(eq(DEVICE_ID), eq((Long) stored));
    }

    @Test
    public void testStopJourneyUsesLastSavedId() throws Exception {
        device.getAttributes().put(MobileJourneyResource.ATTR_JOURNEY_ID, 987_654L);
        Response response = post(API_KEY, "{\"deviceId\":\"" + UNIQUE_ID + "\",\"action\":\"stop\"}");
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(Map.of("ok", true), response.getEntity());

        assertEquals(0L, device.getAttributes().get(MobileJourneyResource.ATTR_JOURNEY_ID));
        verify(registry).end(DEVICE_ID);
        verify(storage).updateObject(eq(device), any(Request.class));

        Event event = capturedEvent();
        assertEquals(Event.TYPE_MOBILE_JOURNEY_ENDED, event.getType());
        assertEquals(987_654L, event.getAttributes().get("journeyId"));
    }

    @Test
    public void testStopJourneyUsesProvidedId() throws Exception {
        device.getAttributes().put(MobileJourneyResource.ATTR_JOURNEY_ID, 111L);
        assertEquals(Response.Status.OK.getStatusCode(),
                post(API_KEY, "{\"deviceId\":\"" + UNIQUE_ID + "\",\"action\":\"stop\",\"journeyId\":222}")
                        .getStatus());
        assertEquals(222L, capturedEvent().getAttributes().get("journeyId"));
    }

    @Test
    public void testEventFailureDoesNotBreakResponse() throws Exception {
        doThrow(new RuntimeException("boom")).when(notifications).updateEvents(anyMap());
        Response response = post(API_KEY, startBody(1000L));
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertEquals(Map.of("ok", true), response.getEntity());
        assertEquals(1000L, device.getAttributes().get(MobileJourneyResource.ATTR_JOURNEY_ID));
        verify(registry).start(DEVICE_ID, 1000L);
    }

    @SuppressWarnings("unchecked")
    private Event capturedEvent() {
        ArgumentCaptor<Map<Event, Position>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notifications).updateEvents(captor.capture());
        return captor.getValue().keySet().iterator().next();
    }
}
