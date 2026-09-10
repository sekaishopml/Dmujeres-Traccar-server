/* Copyright 2026 Anton Tananaev (anton@traccar.org) */
package org.traccar.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Causa de pérdida de red reportada por la app (netCause y banderas).
 * - applyTelemetry persiste mobile.netCause/validated/wifiEnabled/airplane/dataEnabled/simPresent/service/
 *   netConf/causeAt solo si vienen.
 * - Valores fuera de la whitelist se ignoran.
 * - detectChanges adjunta "cause" y "confidence" a los eventos de red sin cambiar sus tipos.
 */
public class MobileTelemetryCauseTest {

    private static final long DEVICE_ID = 7L;

    private Storage storage;
    private MobileTelemetryApplier telemetry;

    @BeforeEach
    public void setUp() {
        storage = mock(Storage.class);
        telemetry = new MobileTelemetryApplier(storage);
    }

    @Test
    public void testPersistsNetCauseAndFlagsWithCauseAt() throws Exception {
        Device device = device();
        long before = System.currentTimeMillis();
        JsonNode root = json("{\"payload\":{\"network\":\"none\",\"netCause\":\"wifi_off_user\","
                + "\"validated\":false,\"wifiEnabled\":false,\"airplane\":false}}");

        telemetry.applyTelemetry(device, root);

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(storage, times(1)).updateObject(captor.capture(), any(Request.class));
        Device persisted = captor.getValue();
        assertEquals("wifi_off_user", persisted.getAttributes().get("mobile.netCause"));
        assertEquals(Boolean.FALSE, persisted.getAttributes().get("mobile.validated"));
        assertEquals(Boolean.FALSE, persisted.getAttributes().get("mobile.wifiEnabled"));
        assertEquals(Boolean.FALSE, persisted.getAttributes().get("mobile.airplane"));
        Object causeAt = persisted.getAttributes().get("mobile.causeAt");
        assertTrue(causeAt instanceof Number);
        long causeAtMs = ((Number) causeAt).longValue();
        assertTrue(causeAtMs >= before && causeAtMs <= System.currentTimeMillis());
    }

    @Test
    public void testIgnoresCauseKeysWhenAbsent() throws Exception {
        Device device = device();
        JsonNode root = json("{\"payload\":{\"network\":\"wifi\",\"battery\":80}}");

        telemetry.applyTelemetry(device, root);

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(storage, times(1)).updateObject(captor.capture(), any(Request.class));
        Device persisted = captor.getValue();
        assertFalse(persisted.getAttributes().containsKey("mobile.netCause"));
        assertFalse(persisted.getAttributes().containsKey("mobile.validated"));
        assertFalse(persisted.getAttributes().containsKey("mobile.wifiEnabled"));
        assertFalse(persisted.getAttributes().containsKey("mobile.airplane"));
        assertFalse(persisted.getAttributes().containsKey("mobile.causeAt"));
    }

    @Test
    public void testRejectsNetCauseOutsideWhitelist() throws Exception {
        Device device = device();
        JsonNode root = json("{\"payload\":{\"network\":\"none\",\"netCause\":\"bogus_value\"}}");

        telemetry.applyTelemetry(device, root);

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(storage, times(1)).updateObject(captor.capture(), any(Request.class));
        Device persisted = captor.getValue();
        assertFalse(persisted.getAttributes().containsKey("mobile.netCause"));
        assertFalse(persisted.getAttributes().containsKey("mobile.causeAt"));
    }

    @Test
    public void testAcceptsEveryWhitelistedNetCause() throws Exception {
        String[] allowed = {"ok", "wifi_off_user", "wifi_lost", "mobile_data_off_suspected",
                "no_coverage_suspected", "airplane", "no_internet", "captive_suspected", "sim_missing"};
        for (String cause : allowed) {
            Storage attemptStorage = mock(Storage.class);
            Device device = device();
            JsonNode root = json("{\"payload\":{\"netCause\":\"" + cause + "\"}}");
            new MobileTelemetryApplier(attemptStorage).applyTelemetry(device, root);
            ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
            verify(attemptStorage, times(1)).updateObject(captor.capture(), any(Request.class));
            assertEquals(cause, captor.getValue().getAttributes().get("mobile.netCause"));
        }
    }

    @Test
    public void testNetCauseOkWithHealthyTelemetryClearsDegraded() throws Exception {
        Device device = device();
        device.getAttributes().put("mobile.degraded", true);
        JsonNode root = json("{\"payload\":{\"network\":\"wifi\",\"netCause\":\"ok\",\"validated\":true}}");

        telemetry.applyTelemetry(device, root);

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(storage, times(1)).updateObject(captor.capture(), any(Request.class));
        assertEquals(Boolean.FALSE, captor.getValue().getAttributes().get("mobile.degraded"));
    }

    @Test
    public void testNetworkLostEventCarriesReceivedCause() throws Exception {
        Device device = device();
        device.getAttributes().put("mobile.network", "none");
        device.getAttributes().put("mobile.battery", 80);
        MobileTelemetryMonitor.TelemetrySnapshot before =
                new MobileTelemetryMonitor.TelemetrySnapshot("on", "wifi", 80, 0L);
        JsonNode root = json("{\"payload\":{\"network\":\"none\",\"netCause\":\"wifi_off_user\"}}");

        List<Event> events = MobileTelemetryMonitor.detectChanges(device, before, root);

        assertEquals(1, events.size());
        assertEquals(Event.TYPE_MOBILE_NETWORK_LOST, events.get(0).getType());
        assertEquals("wifi_off_user", events.get(0).getAttributes().get("cause"));
    }

    @Test
    public void testNetworkEventsFallBackToInferredCause() throws Exception {
        Device device = device();
        device.getAttributes().put("mobile.network", "none");
        device.getAttributes().put("mobile.battery", 80);
        MobileTelemetryMonitor.TelemetrySnapshot before =
                new MobileTelemetryMonitor.TelemetrySnapshot("on", "wifi", 80, 0L);
        JsonNode root = json("{\"payload\":{\"network\":\"none\"}}");

        List<Event> events = MobileTelemetryMonitor.detectChanges(device, before, root);

        assertEquals(1, events.size());
        assertEquals(Event.TYPE_MOBILE_NETWORK_LOST, events.get(0).getType());
        assertEquals("inferred", events.get(0).getAttributes().get("cause"));
    }

    @Test
    public void testWifiLostAndRestoredCarryCause() throws Exception {
        Device wifiLostDevice = device();
        wifiLostDevice.getAttributes().put("mobile.network", "mobile");
        wifiLostDevice.getAttributes().put("mobile.battery", 80);
        MobileTelemetryMonitor.TelemetrySnapshot wifiBefore =
                new MobileTelemetryMonitor.TelemetrySnapshot("on", "wifi", 80, 0L);
        List<Event> wifiLost = MobileTelemetryMonitor.detectChanges(
                wifiLostDevice, wifiBefore, json("{\"payload\":{\"netCause\":\"wifi_lost\"}}"));
        assertEquals(1, wifiLost.size());
        assertEquals(Event.TYPE_MOBILE_WIFI_LOST, wifiLost.get(0).getType());
        assertEquals("wifi_lost", wifiLost.get(0).getAttributes().get("cause"));

        Device restoredDevice = device();
        restoredDevice.getAttributes().put("mobile.network", "wifi");
        restoredDevice.getAttributes().put("mobile.battery", 80);
        MobileTelemetryMonitor.TelemetrySnapshot noneBefore =
                new MobileTelemetryMonitor.TelemetrySnapshot("on", "none", 80, 0L);
        List<Event> restored = MobileTelemetryMonitor.detectChanges(
                restoredDevice, noneBefore, json("{\"payload\":{\"netCause\":\"ok\"}}"));
        assertEquals(1, restored.size());
        assertEquals(Event.TYPE_MOBILE_NETWORK_RESTORED, restored.get(0).getType());
        assertEquals("ok", restored.get(0).getAttributes().get("cause"));
    }

    @Test
    public void testPersistsNewCauseFieldsWithCauseAt() throws Exception {
        Device device = device();
        long before = System.currentTimeMillis();
        JsonNode root = json("{\"payload\":{\"network\":\"none\",\"netCause\":\"sim_missing\","
                + "\"dataEnabled\":false,\"simPresent\":false,\"service\":\"out_of_service\","
                + "\"netConf\":\"confirmed\"}}");

        telemetry.applyTelemetry(device, root);

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(storage, times(1)).updateObject(captor.capture(), any(Request.class));
        Device persisted = captor.getValue();
        assertEquals("sim_missing", persisted.getAttributes().get("mobile.netCause"));
        assertEquals(Boolean.FALSE, persisted.getAttributes().get("mobile.dataEnabled"));
        assertEquals(Boolean.FALSE, persisted.getAttributes().get("mobile.simPresent"));
        assertEquals("out_of_service", persisted.getAttributes().get("mobile.service"));
        assertEquals("confirmed", persisted.getAttributes().get("mobile.netConf"));
        Object causeAt = persisted.getAttributes().get("mobile.causeAt");
        assertTrue(causeAt instanceof Number);
        long causeAtMs = ((Number) causeAt).longValue();
        assertTrue(causeAtMs >= before && causeAtMs <= System.currentTimeMillis());
    }

    @Test
    public void testCauseAtWrittenWhenOnlyNewFieldsPresent() throws Exception {
        Device device = device();
        JsonNode root = json("{\"payload\":{\"dataEnabled\":true}}");

        telemetry.applyTelemetry(device, root);

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(storage, times(1)).updateObject(captor.capture(), any(Request.class));
        Device persisted = captor.getValue();
        assertEquals(Boolean.TRUE, persisted.getAttributes().get("mobile.dataEnabled"));
        assertTrue(persisted.getAttributes().get("mobile.causeAt") instanceof Number);
    }

    @Test
    public void testIgnoresNewCauseKeysWhenAbsent() throws Exception {
        Device device = device();
        JsonNode root = json("{\"payload\":{\"network\":\"wifi\",\"battery\":80}}");

        telemetry.applyTelemetry(device, root);

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(storage, times(1)).updateObject(captor.capture(), any(Request.class));
        Device persisted = captor.getValue();
        assertFalse(persisted.getAttributes().containsKey("mobile.dataEnabled"));
        assertFalse(persisted.getAttributes().containsKey("mobile.simPresent"));
        assertFalse(persisted.getAttributes().containsKey("mobile.service"));
        assertFalse(persisted.getAttributes().containsKey("mobile.netConf"));
        assertFalse(persisted.getAttributes().containsKey("mobile.fixReceived"));
        assertFalse(persisted.getAttributes().containsKey("mobile.fixRejected"));
        assertFalse(persisted.getAttributes().containsKey("mobile.fixEnqueued"));
        assertFalse(persisted.getAttributes().containsKey("mobile.permFine"));
        assertFalse(persisted.getAttributes().containsKey("mobile.permBackground"));
        assertFalse(persisted.getAttributes().containsKey("mobile.gpsEnabled"));
    }

    @Test
    public void testPersistsCaptureCountersAndFlags() throws Exception {
        Device device = device();
        JsonNode root = json("{\"payload\":{\"network\":\"wifi\",\"fixReceived\":120,"
                + "\"fixRejected\":118,\"fixEnqueued\":2,\"permFine\":true,"
                + "\"permBackground\":false,\"gpsEnabled\":true}}");

        telemetry.applyTelemetry(device, root);

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(storage, times(1)).updateObject(captor.capture(), any(Request.class));
        Device persisted = captor.getValue();
        assertEquals(120L, ((Number) persisted.getAttributes().get("mobile.fixReceived")).longValue());
        assertEquals(118L, ((Number) persisted.getAttributes().get("mobile.fixRejected")).longValue());
        assertEquals(2L, ((Number) persisted.getAttributes().get("mobile.fixEnqueued")).longValue());
        assertEquals(Boolean.TRUE, persisted.getAttributes().get("mobile.permFine"));
        assertEquals(Boolean.FALSE, persisted.getAttributes().get("mobile.permBackground"));
        assertEquals(Boolean.TRUE, persisted.getAttributes().get("mobile.gpsEnabled"));
    }

    @Test
    public void testPersistsGnssAndPolling() throws Exception {
        Device device = device();
        JsonNode root = json("{\"payload\":{\"network\":\"wifi\",\"gnssUsed\":7,"
                + "\"gnssTotal\":12,\"pollActive\":true}}");

        telemetry.applyTelemetry(device, root);

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(storage, times(1)).updateObject(captor.capture(), any(Request.class));
        Device persisted = captor.getValue();
        assertEquals(7L, ((Number) persisted.getAttributes().get("mobile.gnssUsed")).longValue());
        assertEquals(12L, ((Number) persisted.getAttributes().get("mobile.gnssTotal")).longValue());
        assertEquals(Boolean.TRUE, persisted.getAttributes().get("mobile.pollActive"));
    }

    @Test
    public void testIgnoresGnssWhenAbsent() throws Exception {
        Device device = device();
        JsonNode root = json("{\"payload\":{\"network\":\"wifi\",\"battery\":80}}");

        telemetry.applyTelemetry(device, root);

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(storage, times(1)).updateObject(captor.capture(), any(Request.class));
        Device persisted = captor.getValue();
        assertFalse(persisted.getAttributes().containsKey("mobile.gnssUsed"));
        assertFalse(persisted.getAttributes().containsKey("mobile.gnssTotal"));
        assertFalse(persisted.getAttributes().containsKey("mobile.pollActive"));
    }

    @Test
    public void testAcceptsEveryWhitelistedNetConfAndService() throws Exception {
        String[] confAllowed = {"confirmed", "suspected"};
        for (String conf : confAllowed) {
            Storage attemptStorage = mock(Storage.class);
            Device device = device();
            JsonNode root = json("{\"payload\":{\"netConf\":\"" + conf + "\"}}");
            new MobileTelemetryApplier(attemptStorage).applyTelemetry(device, root);
            ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
            verify(attemptStorage, times(1)).updateObject(captor.capture(), any(Request.class));
            assertEquals(conf, captor.getValue().getAttributes().get("mobile.netConf"));
            assertTrue(captor.getValue().getAttributes().containsKey("mobile.causeAt"));
        }
        String[] serviceAllowed = {"in_service", "out_of_service", "emergency", "unknown"};
        for (String service : serviceAllowed) {
            Storage attemptStorage = mock(Storage.class);
            Device device = device();
            JsonNode root = json("{\"payload\":{\"service\":\"" + service + "\"}}");
            new MobileTelemetryApplier(attemptStorage).applyTelemetry(device, root);
            ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
            verify(attemptStorage, times(1)).updateObject(captor.capture(), any(Request.class));
            assertEquals(service, captor.getValue().getAttributes().get("mobile.service"));
            assertTrue(captor.getValue().getAttributes().containsKey("mobile.causeAt"));
        }
    }

    @Test
    public void testRejectsInvalidNetConfAndService() throws Exception {
        Device device = device();
        JsonNode root = json("{\"payload\":{\"netConf\":\"bogus\",\"service\":\"bogus\"}}");

        telemetry.applyTelemetry(device, root);

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(storage, times(1)).updateObject(captor.capture(), any(Request.class));
        Device persisted = captor.getValue();
        assertFalse(persisted.getAttributes().containsKey("mobile.netConf"));
        assertFalse(persisted.getAttributes().containsKey("mobile.service"));
        assertFalse(persisted.getAttributes().containsKey("mobile.causeAt"));
    }

    @Test
    public void testNetworkLostEventCarriesExplicitConfidence() throws Exception {
        Device device = device();
        device.getAttributes().put("mobile.network", "none");
        device.getAttributes().put("mobile.battery", 80);
        MobileTelemetryMonitor.TelemetrySnapshot before =
                new MobileTelemetryMonitor.TelemetrySnapshot("on", "wifi", 80, 0L);
        JsonNode root = json(
                "{\"payload\":{\"network\":\"none\",\"netCause\":\"sim_missing\",\"netConf\":\"confirmed\"}}");

        List<Event> events = MobileTelemetryMonitor.detectChanges(device, before, root);

        assertEquals(1, events.size());
        assertEquals(Event.TYPE_MOBILE_NETWORK_LOST, events.get(0).getType());
        assertEquals("sim_missing", events.get(0).getAttributes().get("cause"));
        assertEquals("confirmed", events.get(0).getAttributes().get("confidence"));
    }

    @Test
    public void testNetworkEventsFallBackToInferredConfidence() throws Exception {
        Device device = device();
        device.getAttributes().put("mobile.network", "none");
        device.getAttributes().put("mobile.battery", 80);
        MobileTelemetryMonitor.TelemetrySnapshot before =
                new MobileTelemetryMonitor.TelemetrySnapshot("on", "wifi", 80, 0L);

        List<Event> missing = MobileTelemetryMonitor.detectChanges(
                device, before, json("{\"payload\":{\"network\":\"none\"}}"));
        assertEquals(1, missing.size());
        assertEquals("inferred", missing.get(0).getAttributes().get("confidence"));

        List<Event> invalid = MobileTelemetryMonitor.detectChanges(
                device, before, json("{\"payload\":{\"network\":\"none\",\"netConf\":\"bogus\"}}"));
        assertEquals(1, invalid.size());
        assertEquals("inferred", invalid.get(0).getAttributes().get("confidence"));
    }

    @Test
    public void testWifiLostAndRestoredCarryConfidence() throws Exception {
        Device wifiLostDevice = device();
        wifiLostDevice.getAttributes().put("mobile.network", "mobile");
        wifiLostDevice.getAttributes().put("mobile.battery", 80);
        MobileTelemetryMonitor.TelemetrySnapshot wifiBefore =
                new MobileTelemetryMonitor.TelemetrySnapshot("on", "wifi", 80, 0L);
        List<Event> wifiLost = MobileTelemetryMonitor.detectChanges(
                wifiLostDevice, wifiBefore,
                json("{\"payload\":{\"netCause\":\"wifi_lost\",\"netConf\":\"suspected\"}}"));
        assertEquals(1, wifiLost.size());
        assertEquals(Event.TYPE_MOBILE_WIFI_LOST, wifiLost.get(0).getType());
        assertEquals("suspected", wifiLost.get(0).getAttributes().get("confidence"));

        Device restoredDevice = device();
        restoredDevice.getAttributes().put("mobile.network", "wifi");
        restoredDevice.getAttributes().put("mobile.battery", 80);
        MobileTelemetryMonitor.TelemetrySnapshot noneBefore =
                new MobileTelemetryMonitor.TelemetrySnapshot("on", "none", 80, 0L);
        List<Event> restored = MobileTelemetryMonitor.detectChanges(
                restoredDevice, noneBefore, json("{\"payload\":{\"netCause\":\"ok\"}}"));
        assertEquals(1, restored.size());
        assertEquals(Event.TYPE_MOBILE_NETWORK_RESTORED, restored.get(0).getType());
        assertEquals("inferred", restored.get(0).getAttributes().get("confidence"));
    }

    private static Device device() {
        Device device = new Device();
        device.setId(DEVICE_ID);
        device.setUniqueId("device-123");
        return device;
    }

    private static JsonNode json(String value) throws Exception {
        return new ObjectMapper().readTree(value);
    }
}
