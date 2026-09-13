/* Copyright 2026 Anton Tananaev (anton@traccar.org) */
package org.traccar.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.model.Device;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Presupuesto de tc_devices.attributes (VARCHAR(4000)): batteryHistory + ~25
 * claves mobile.* pueden desbordar la columna y la telemetría del device moriría
 * en silencio. Con atributos sobre el presupuesto blando se recorta
 * batteryHistory; si incluso así excede el duro, se omite el UPDATE completo
 * (el ACK sigue accepted — la posición NO se pierde).
 */
public class MobileTelemetryBudgetTest {

    private Storage storage;
    private MobileTelemetryApplier telemetry;

    @BeforeEach
    public void setUp() {
        storage = mock(Storage.class);
        telemetry = new MobileTelemetryApplier(storage, mock(MobilePresenceTracker.class));
    }

    @Test
    public void testLargeHistoryWithManyKeysDropsHistoryAndStaysWithinBudget() throws Exception {
        Device device = deviceWithHistory();
        // Sin history cabe (≈3489 < 3900 duro); con history excede el blando (≈3901 > 3800).
        device.getAttributes().put("mobile.rejectBreakdown", "x".repeat(3460));
        JsonNode root = json("{\"payload\":{\"network\":\"wifi\",\"battery\":80}}");

        assertDoesNotThrow(() -> telemetry.applyTelemetry(device, root));

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(storage).updateObject(captor.capture(), any(Request.class));
        Device persisted = captor.getValue();
        String serialized = MobileTelemetryApplier.serializeAttributes(persisted);
        assertTrue(serialized.length() <= 4000,
                "attributes must fit VARCHAR(4000), got " + serialized.length());
        assertFalse(persisted.getAttributes().containsKey("mobile.batteryHistory"));
        assertEquals("wifi", persisted.getAttributes().get("mobile.network"));
    }

    @Test
    public void testHardBudgetExceededSkipsUpdateButDoesNotThrow() throws Exception {
        Device device = deviceWithHistory();
        // Clave ajena al applier, enorme: sin batteryHistory el presupuesto duro
        // sigue excedido, así que el UPDATE completo se omite (sin excepción).
        device.getAttributes().put("mobile.rejectBreakdown", ("x".repeat(100) + "|").repeat(38));

        JsonNode root = json("{\"payload\":{\"network\":\"wifi\",\"battery\":80}}");

        assertDoesNotThrow(() -> telemetry.applyTelemetry(device, root));

        verify(storage, never()).updateObject(any(), any(Request.class));
    }

    @Test
    public void testSmallAttributesKeepBatteryHistory() throws Exception {
        Device device = new Device();
        device.setId(7L);
        device.setUniqueId("device-123");
        JsonNode root = json("{\"payload\":{\"network\":\"wifi\",\"battery\":80}}");

        telemetry.applyTelemetry(device, root);

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(storage).updateObject(captor.capture(), any(Request.class));
        assertTrue(captor.getValue().getAttributes().containsKey("mobile.batteryHistory"));
    }

    @Test
    public void testRingIsCappedAt24Samples() throws Exception {
        Device device = new Device();
        device.setId(7L);
        device.setUniqueId("device-123");
        // 30 muestras previas de hace >1 min (no se colapsan): el ring recorta a 24.
        org.json.JSONArray history = new org.json.JSONArray();
        for (int i = 0; i < 30; i++) {
            org.json.JSONArray sample = new org.json.JSONArray();
            sample.put(1_700_000_000L + i * 60_000);
            sample.put(50);
            history.put(sample);
        }
        device.getAttributes().put("mobile.batteryHistory", history.toString());
        JsonNode root = json("{\"payload\":{\"battery\":80}}");

        telemetry.applyTelemetry(device, root);

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(storage).updateObject(captor.capture(), any(Request.class));
        org.json.JSONArray persisted = new org.json.JSONArray(
                (String) captor.getValue().getAttributes().get("mobile.batteryHistory"));
        assertTrue(persisted.length() <= MobileTelemetryApplier.BATTERY_HISTORY_SAMPLES);
    }

    private Device deviceWithHistory() throws Exception {
        Device device = new Device();
        device.setId(7L);
        device.setUniqueId("device-123");
        org.json.JSONArray history = new org.json.JSONArray();
        for (int i = 0; i < 24; i++) {
            org.json.JSONArray sample = new org.json.JSONArray();
            sample.put(1_700_000_000L + i * 60_000);
            sample.put(50);
            history.put(sample);
        }
        device.getAttributes().put("mobile.batteryHistory", history.toString());
        return device;
    }

    private static JsonNode json(String value) throws Exception {
        return new ObjectMapper().readTree(value);
    }
}
