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
import org.traccar.mobile.MobileDiagnosticsService;
import org.traccar.model.Device;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El recurso debe replicar el modelo de autenticación de {@link MobileConfigResource}
 * (cabecera X-Api-Key + mobile.http.enable, dispositivo resuelto por uniqueId) y mapear los
 * códigos HTTP del servicio. Pruebas de unidad: se llama al método del recurso directamente,
 * con el servicio real y las dependencias de BD mockeadas (sin contenedor Jersey).
 */
public class MobileDiagnosticsResourceTest {

    private static final long DEVICE_ID = 7L;
    private static final String UNIQUE_ID = "device-123";
    private static final String API_KEY = "test-shared-key";

    private Config config;
    private Storage storage;
    private NotificationManager notificationManager;
    private Device device;
    private MobileDiagnosticsResource resource;

    @BeforeEach
    public void setUp() throws Exception {
        config = mock(Config.class);
        when(config.getBoolean(Keys.MOBILE_HTTP_ENABLE)).thenReturn(true);
        when(config.getString(Keys.MOBILE_HTTP_API_KEY)).thenReturn(API_KEY);

        storage = mock(Storage.class);
        device = new Device();
        device.setId(DEVICE_ID);
        device.setUniqueId(UNIQUE_ID);
        when(storage.getObject(any(), any(Request.class))).thenReturn(device);

        notificationManager = mock(NotificationManager.class);
        MobileDiagnosticsService service = new MobileDiagnosticsService(
                storage, new ObjectMapper(), notificationManager, mock(CacheManager.class));

        resource = new MobileDiagnosticsResource(config, service);
        Field field = BaseResource.class.getDeclaredField("storage");
        field.setAccessible(true);
        field.set(resource, storage);
    }

    @Test
    public void testChannelDisabledIsNotFound() throws Exception {
        when(config.getBoolean(Keys.MOBILE_HTTP_ENABLE)).thenReturn(false);
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), status("{\"ts\":1}"));
        verify(storage, never()).getObject(any(), any(Request.class));
    }

    @Test
    public void testWrongApiKeyIsUnauthorized() throws Exception {
        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), status(API_KEY + "x", UNIQUE_ID, null, "{\"ts\":1}"));
        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), status(null, UNIQUE_ID, null, "{\"ts\":1}"));
        verify(storage, never()).getObject(any(), any(Request.class));
    }

    @Test
    public void testUnconfiguredApiKeyIsUnauthorized() throws Exception {
        when(config.getString(Keys.MOBILE_HTTP_API_KEY)).thenReturn(null);
        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), status(API_KEY, UNIQUE_ID, null, "{\"ts\":1}"));
    }

    @Test
    public void testMissingDeviceIdentifierIsBadRequest() throws Exception {
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), status(API_KEY, null, null, "{\"ts\":1}"));
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), status(API_KEY, "   ", "", "{\"ts\":1}"));
        verify(storage, never()).getObject(any(), any(Request.class));
    }

    @Test
    public void testDeviceIsResolvedByTrimmedUniqueIdHeader() throws Exception {
        assertEquals(204, status(API_KEY, "  " + UNIQUE_ID + "  ", null, "{\"ts\":1}"));
        ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
        verify(storage).getObject(any(), captor.capture());
        Condition.Compare condition = (Condition.Compare) captor.getValue().getCondition();
        assertEquals("uniqueId", condition.getColumn());
        assertEquals(UNIQUE_ID, condition.getValue());
        assertNotNull(device.getAttributes().get("lastDiagnostics"));
    }

    @Test
    public void testQueryParameterIsTheFallback() throws Exception {
        assertEquals(204, status(API_KEY, null, UNIQUE_ID, "{\"ts\":1}"));
        ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
        verify(storage).getObject(any(), captor.capture());
        assertEquals(UNIQUE_ID, ((Condition.Compare) captor.getValue().getCondition()).getValue());
    }

    @Test
    public void testUnknownDeviceIsNotFound() throws Exception {
        when(storage.getObject(any(), any(Request.class))).thenReturn(null);
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), status("{\"ts\":1}"));
        verify(storage, never()).updateObject(any(), any(Request.class));
    }

    @Test
    public void testAcceptedReportIsNoContentWithoutBody() throws Exception {
        Response response = resource.submit(
                API_KEY, UNIQUE_ID, null,
                "{\"deviceId\":7,\"ts\":123,\"report\":{\"power\":{\"battery\":42},\"extra\":\"x\"}}");
        assertEquals(204, response.getStatus());
        assertNull(response.getEntity());
        assertNotNull(device.getAttributes().get("lastDiagnostics"));
        assertNotNull(device.getAttributes().get("lastDiagnosticsAt"));
        verify(storage).updateObject(any(), any(Request.class));
    }

    @Test
    public void testOversizedBodyIsRequestEntityTooLarge() throws Exception {
        String big = "{\"report\":{\"health\":{\"lastStartError\":\"" + "b".repeat(10_000) + "\"}}}";
        assertEquals(Response.Status.REQUEST_ENTITY_TOO_LARGE.getStatusCode(),
                resource.submit(API_KEY, UNIQUE_ID, null, big).getStatus());
    }

    @Test
    public void testBodyAtExactlyTenThousandBytesIsAccepted() throws Exception {
        String base = "{\"report\":{\"app\":{\"versionName\":\"";
        String tail = "\"}}}";
        String filler = "b".repeat(10_000 - base.length() - tail.length());
        assertEquals(204, resource.submit(API_KEY, UNIQUE_ID, null, base + filler + tail).getStatus());
    }

    @Test
    public void testMalformedJsonIsBadRequest() throws Exception {
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(),
                resource.submit(API_KEY, UNIQUE_ID, null, "{oops").getStatus());
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(),
                resource.submit(API_KEY, UNIQUE_ID, null, "[1,2,3]").getStatus());
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(),
                resource.submit(API_KEY, UNIQUE_ID, null, "   ").getStatus());
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(),
                resource.submit(API_KEY, UNIQUE_ID, null, null).getStatus());
    }

    @Test
    public void testDeviceIdMismatchIsForbidden() throws Exception {
        assertEquals(Response.Status.FORBIDDEN.getStatusCode(),
                resource.submit(API_KEY, UNIQUE_ID, null, "{\"deviceId\":99,\"ts\":1}").getStatus());
        verify(storage, never()).updateObject(any(), any(Request.class));
    }

    @Test
    public void testSecondReportWithinWindowIsIgnored() throws Exception {
        assertEquals(204, resource.submit(API_KEY, UNIQUE_ID, null, "{\"ts\":1}").getStatus());
        // mismo instante: el servicio responde THROTTLED antes de parsear (204 sin escribir)
        assertEquals(204, resource.submit(API_KEY, UNIQUE_ID, null, "{\"ts\":2}").getStatus());
        verify(storage, times(1)).updateObject(any(), any(Request.class));
        assertEquals(1L, ((Number) lastDiagnosticsTs()).longValue());
    }

    private Object lastDiagnosticsTs() {
        String json = (String) device.getAttributes().get("lastDiagnostics");
        try {
            return new ObjectMapper().readTree(json).get("ts").asLong();
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private int status(String body) throws Exception {
        return status(API_KEY, UNIQUE_ID, null, body);
    }

    private int status(String apiKey, String header, String query, String body) throws Exception {
        return resource.submit(apiKey, header, query, body).getStatus();
    }
}
