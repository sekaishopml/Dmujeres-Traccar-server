/* Copyright 2026 Anton Tananaev (anton@traccar.org) */
package org.traccar.mobile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.traccar.database.DeviceLookupService;
import org.traccar.model.Device;
import org.traccar.session.ConnectionManager;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LWT del broker como offline + throttle de WARNs de validación.
 * - El will (messageId "lwt-&lt;now&gt;", sequence 0, presence network=none) llega como
 *   publish normal al morir el TCP en Doze: debe marcar offline sin persistir ni WARN.
 * - Los rechazos genuinos (sequence&lt;=0 no-LWT, envelope roto) solo loguean la 1ª vez
 *   y cada 100.
 * - El validador sigue rechazando sequence 0 (no se debilita para el no-LWT).
 */
public class MobileLwtThrottleTest {

    private static final String DEVICE_ID = "device-123";
    private static final long DATABASE_ID = 7L;

    @Test
    public void testIsLwtHeartbeatMatchesBrokerWill() {
        assertTrue(MobileIngestionService.isLwtHeartbeat(lwtEnvelope("lwt-1725000000000", 0)));
    }

    @Test
    public void testIsLwtHeartbeatMatchesSequenceZeroPresence() {
        assertTrue(MobileIngestionService.isLwtHeartbeat(lwtEnvelope("01J00000000000000000000000", 0)));
    }

    @Test
    public void testNormalPresenceIsNotLwt() {
        MobileEnvelope envelope = lwtEnvelope("01J00000000000000000000000", 5);
        assertFalse(MobileIngestionService.isLwtHeartbeat(envelope));
    }

    @Test
    public void testPositionSequenceZeroIsNotLwt() {
        MobileEnvelope envelope = lwtEnvelope("01J00000000000000000000000", 0);
        envelope.setType("position");
        assertFalse(MobileIngestionService.isLwtHeartbeat(envelope));
    }

    @Test
    public void testIsLwtHeartbeatNullSafe() {
        assertFalse(MobileIngestionService.isLwtHeartbeat(null));
    }

    @Test
    public void testValidatorStillRejectsZeroSequence() {
        Instant now = Instant.now();
        MobileEnvelope presence = lwtEnvelope("01J00000000000000000000000", 0);
        presence.setSentAt(now.toString());
        presence.setObservedAt(now.toString());
        assertThrows(IllegalArgumentException.class,
                () -> MobileEnvelopeValidator.validate(presence, DEVICE_ID, now));
        MobileEnvelope position = lwtEnvelope("01J00000000000000000000001", 0);
        position.setType("position");
        position.setSentAt(now.toString());
        position.setObservedAt(now.toString());
        assertThrows(IllegalArgumentException.class,
                () -> MobileEnvelopeValidator.validate(position, DEVICE_ID, now));
    }

    @Test
    public void testLwtMarksOfflineWithoutPersisting() throws Exception {
        Device device = device();
        DeviceLookupService devices = mock(DeviceLookupService.class);
        when(devices.lookup(any(String[].class))).thenReturn(device);
        ConnectionManager connectionManager = mock(ConnectionManager.class);
        MobileMessageStore messages = mock(MobileMessageStore.class);
        MobileAtomicPersistence atomic = mock(MobileAtomicPersistence.class);
        MobileIngestionService service = service(devices, connectionManager, messages, atomic);

        byte[] payload = new ObjectMapper().writeValueAsBytes(lwtMap("lwt-1725000000000"));
        MobileIngestionService.Result result =
                service.process(payload, DEVICE_ID).toCompletableFuture().join();

        assertEquals(MobileIngestionService.AckStatus.ACCEPTED, result.status());
        assertNull(result.envelope());
        verify(connectionManager).updateDevice(eq(DATABASE_ID), eq(Device.STATUS_OFFLINE), any());
        verify(connectionManager).updateDevice(eq(true), any(Device.class));
        verify(messages, never()).reserve(anyLong(), any(), any());
        verify(atomic, never()).claim(any(), anyLong());
    }

    @Test
    public void testLwtSequenceZeroPresenceWithoutPrefixMarksOffline() throws Exception {
        DeviceLookupService devices = mock(DeviceLookupService.class);
        when(devices.lookup(any(String[].class))).thenReturn(device());
        ConnectionManager connectionManager = mock(ConnectionManager.class);
        MobileMessageStore messages = mock(MobileMessageStore.class);
        MobileIngestionService service = service(devices, connectionManager, messages, null);

        byte[] payload = new ObjectMapper().writeValueAsBytes(lwtMap("01J00000000000000000000000"));
        MobileIngestionService.Result result =
                service.process(payload, DEVICE_ID).toCompletableFuture().join();

        assertEquals(MobileIngestionService.AckStatus.ACCEPTED, result.status());
        assertNull(result.envelope());
        verify(connectionManager).updateDevice(eq(DATABASE_ID), eq(Device.STATUS_OFFLINE), any());
        verify(messages, never()).reserve(anyLong(), any(), any());
    }

    @Test
    public void testLwtUnknownDeviceDropsQuietly() throws Exception {
        DeviceLookupService devices = mock(DeviceLookupService.class);
        when(devices.lookup(any(String[].class))).thenReturn(null);
        ConnectionManager connectionManager = mock(ConnectionManager.class);
        MobileMessageStore messages = mock(MobileMessageStore.class);
        MobileIngestionService service = service(devices, connectionManager, messages, null);

        byte[] payload = new ObjectMapper().writeValueAsBytes(lwtMap("lwt-1725000000000"));
        MobileIngestionService.Result result =
                service.process(payload, DEVICE_ID).toCompletableFuture().join();

        assertEquals(MobileIngestionService.AckStatus.ACCEPTED, result.status());
        assertNull(result.envelope());
        verify(connectionManager, never()).updateDevice(anyLong(), any(), any());
        verify(messages, never()).reserve(anyLong(), any(), any());
    }

    @Test
    public void testShouldLogInvalidThrottle() {
        assertTrue(MobileIngestionService.shouldLogInvalid(1));
        assertFalse(MobileIngestionService.shouldLogInvalid(2));
        assertFalse(MobileIngestionService.shouldLogInvalid(99));
        assertTrue(MobileIngestionService.shouldLogInvalid(100));
        assertFalse(MobileIngestionService.shouldLogInvalid(101));
        assertTrue(MobileIngestionService.shouldLogInvalid(200));
    }

    @Test
    public void testBrokenEnvelopesAreCountedWithoutException() {
        MobileIngestionService service = service(null, null, null, null);
        for (int i = 0; i < 250; i++) {
            MobileIngestionService.Result result =
                    service.process("not-json".getBytes(), DEVICE_ID).toCompletableFuture().join();
            assertEquals(MobileIngestionService.AckStatus.INVALID, result.status());
            assertNull(result.envelope());
        }
        assertEquals(250, service.getInvalidMessageCount());
    }

    @Test
    public void testNonLwtZeroSequenceCountsAsInvalid() throws Exception {
        MobileIngestionService service = service(null, null, null, null);
        Instant now = Instant.now();
        String json = "{\"schema\":1,\"type\":\"position\",\"messageId\":\"01J00000000000000000000000\","
                + "\"deviceId\":\"" + DEVICE_ID + "\",\"sequence\":0,"
                + "\"sentAt\":\"" + now + "\",\"observedAt\":\"" + now + "\","
                + "\"payload\":{\"latitude\":1.0,\"longitude\":2.0}}";
        MobileIngestionService.Result result =
                service.process(json.getBytes(), DEVICE_ID).toCompletableFuture().join();
        assertEquals(MobileIngestionService.AckStatus.INVALID, result.status());
        assertEquals(1, service.getInvalidMessageCount());
    }

    private static MobileIngestionService service(
            DeviceLookupService devices, ConnectionManager connectionManager,
            MobileMessageStore messages, MobileAtomicPersistence atomic) {
        return new MobileIngestionService(
                null, new ObjectMapper(), devices, messages, atomic,
                null, null, connectionManager, null, null, null, null);
    }

    private static Device device() {
        Device device = new Device();
        device.setId(DATABASE_ID);
        device.setUniqueId(DEVICE_ID);
        return device;
    }

    private static MobileEnvelope lwtEnvelope(String messageId, long sequence) {
        MobileEnvelope envelope = new MobileEnvelope();
        envelope.setSchema(1);
        envelope.setType("presence");
        envelope.setMessageId(messageId);
        envelope.setDeviceId(DEVICE_ID);
        envelope.setSequence(sequence);
        envelope.setSentAt("2026-09-03T12:00:00Z");
        envelope.setObservedAt("2026-09-03T12:00:00Z");
        return envelope;
    }

    private static Map<String, Object> lwtMap(String messageId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("network", "none");
        payload.put("gps", "unknown");
        payload.put("battery", -1);
        payload.put("pending", 0);
        Map<String, Object> root = new HashMap<>();
        root.put("schema", 1);
        root.put("type", "presence");
        root.put("messageId", messageId);
        root.put("deviceId", DEVICE_ID);
        root.put("sequence", 0);
        root.put("sentAt", Instant.now().toString());
        root.put("observedAt", Instant.now().toString());
        root.put("payload", payload);
        return root;
    }
}
