/* Copyright 2026 Anton Tananaev (anton@traccar.org) */
package org.traccar.api.resource;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.api.BaseResource;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Device;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * La config remota debe exponer los switches L1 por dispositivo
 * (l1_pending_intent_enabled, store_all_enabled, l1_max_update_delay_ms,
 * min_interval_seconds) con los defaults de la app cuando el device no tiene
 * atributos, y con los mobile.* del device cuando existen. Pruebas de unidad:
 * se llama al método del recurso directamente, con Storage mockeado.
 */
public class MobileConfigResourceTest {

    private static final String UNIQUE_ID = "device-123";
    private static final String API_KEY = "test-shared-key";

    private Config config;
    private Device device;
    private MobileConfigResource resource;

    @BeforeEach
    public void setUp() throws Exception {
        config = mock(Config.class);
        when(config.getBoolean(Keys.MOBILE_HTTP_ENABLE)).thenReturn(true);
        when(config.getString(Keys.MOBILE_HTTP_API_KEY)).thenReturn(API_KEY);

        device = new Device();
        device.setUniqueId(UNIQUE_ID);
        Storage storage = mock(Storage.class);
        when(storage.getObject(any(), any(Request.class))).thenReturn(device);

        resource = new MobileConfigResource(config);
        Field field = BaseResource.class.getDeclaredField("storage");
        field.setAccessible(true);
        field.set(resource, storage);
    }

    @Test
    public void testL1DefaultsWhenDeviceHasNoAttributes() throws Exception {
        Map<String, Object> body = configBody();
        assertEquals(false, body.get("l1_pending_intent_enabled"));
        assertEquals(false, body.get("store_all_enabled"));
        assertEquals(60000L, body.get("l1_max_update_delay_ms"));
        assertEquals(10L, body.get("min_interval_seconds"));
    }

    @Test
    public void testL1DeviceAttributesAreApplied() throws Exception {
        device.getAttributes().put("mobile.l1PendingIntentEnabled", true);
        device.getAttributes().put("mobile.storeAllEnabled", true);
        device.getAttributes().put("mobile.l1MaxUpdateDelayMs", 120000);
        device.getAttributes().put("mobile.minIntervalSeconds", 30);

        Map<String, Object> body = configBody();
        assertEquals(true, body.get("l1_pending_intent_enabled"));
        assertEquals(true, body.get("store_all_enabled"));
        assertEquals(120000L, body.get("l1_max_update_delay_ms"));
        assertEquals(30L, body.get("min_interval_seconds"));
    }

    @Test
    public void testL1StringAttributesAreParsed() throws Exception {
        device.getAttributes().put("mobile.l1PendingIntentEnabled", "true");
        device.getAttributes().put("mobile.storeAllEnabled", "false");
        device.getAttributes().put("mobile.l1MaxUpdateDelayMs", "45000");
        device.getAttributes().put("mobile.minIntervalSeconds", "60");

        Map<String, Object> body = configBody();
        assertEquals(true, body.get("l1_pending_intent_enabled"));
        assertEquals(false, body.get("store_all_enabled"));
        assertEquals(45000L, body.get("l1_max_update_delay_ms"));
        assertEquals(60L, body.get("min_interval_seconds"));
    }

    @Test
    public void testChannelDisabledIsNotFound() throws Exception {
        when(config.getBoolean(Keys.MOBILE_HTTP_ENABLE)).thenReturn(false);
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), status());
    }

    @Test
    public void testWrongApiKeyIsUnauthorized() throws Exception {
        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(),
                resource.getConfig("wrong-key", UNIQUE_ID, null).getStatus());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> configBody() throws Exception {
        Response response = resource.getConfig(API_KEY, UNIQUE_ID, null);
        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        return (Map<String, Object>) response.getEntity();
    }

    private int status() throws Exception {
        return resource.getConfig(API_KEY, UNIQUE_ID, null).getStatus();
    }
}
