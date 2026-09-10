/* Copyright 2026 Anton Tananaev (anton@traccar.org) */
package org.traccar.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.database.NotificationManager;
import org.traccar.model.Device;
import org.traccar.model.MobileMessage;
import org.traccar.session.ConnectionManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Verifica la lógica de estados del canal de presencia:
 * - FIX FANTASMA: al terminar jornada se persiste en BD journeyId=0 + journeyEndedAt.
 * - En inicio no se rompe nada (journeyId se persiste vía applyTelemetry).
 * - Decisión pura de degradación (rttMs / network) de MobileTelemetryApplier.
 */
public class MobilePresenceServiceTest {

    private static final long DEVICE_ID = 7L;

    private Storage storage;
    private MobileJourneyRegistry journeyRegistry;
    private ConnectionManager connectionManager;
    private MobilePresenceService service;

    @BeforeEach
    public void setUp() {
        storage = mock(Storage.class);
        journeyRegistry = mock(MobileJourneyRegistry.class);
        connectionManager = mock(ConnectionManager.class);
        MobileTelemetryApplier telemetry = new MobileTelemetryApplier(storage);
        service = new MobilePresenceService(
                mock(MobileMessageStore.class),
                journeyRegistry,
                connectionManager,
                mock(NotificationManager.class),
                telemetry,
                storage);
    }

    @Test
    public void testEndedPersistsJourneyResetInDatabase() throws Exception {
        Device device = device();
        // journeyId>0 en el payload: applyTelemetry lo persiste primero, y la rama
        // ended debe sobreescribirlo a 0 en BD para evitar la jornada fantasma.
        JsonNode root = json("{\"payload\":{\"journeyEnded\":true,\"journeyId\":55,"
                + "\"battery\":80,\"network\":\"wifi\"}}");

        MobilePresenceService.PresenceOutcome outcome =
                service.handlePresence(device, envelope(), root, message());

        assertEquals(MobilePresenceService.PresenceOutcome.ACCEPTED, outcome);
        verify(journeyRegistry).end(DEVICE_ID);

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        // 1) applyTelemetry, 2) la persistencia explícita del fin de jornada.
        verify(storage, times(2)).updateObject(captor.capture(), any(Request.class));
        Device persisted = captor.getAllValues().get(1);
        assertEquals(0L, ((Number) persisted.getAttributes().get("mobile.journeyId")).longValue());
        Object endedAt = persisted.getAttributes().get("mobile.journeyEndedAt");
        assertNotNull(endedAt);
        assertTrue(((Number) endedAt).longValue() > 0);
        verify(connectionManager).updateDevice(eq(DEVICE_ID), eq(Device.STATUS_OFFLINE), any());
    }

    @Test
    public void testStartedKeepsJourneyIdWithoutReset() throws Exception {
        Device device = device();
        JsonNode root = json("{\"payload\":{\"journeyStarted\":true,\"journeyId\":42,"
                + "\"battery\":90,\"network\":\"mobile\"}}");

        MobilePresenceService.PresenceOutcome outcome =
                service.handlePresence(device, envelope(), root, message());

        assertEquals(MobilePresenceService.PresenceOutcome.ACCEPTED, outcome);
        verify(journeyRegistry).start(DEVICE_ID, 42L);
        verify(journeyRegistry, never()).end(anyLong());

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        // Solo persiste applyTelemetry (journeyId incluido en payload); no debe
        // aparecer journeyEndedAt ni journeyId=0.
        verify(storage, times(1)).updateObject(captor.capture(), any(Request.class));
        Device persisted = captor.getValue();
        assertEquals(42L, ((Number) persisted.getAttributes().get("mobile.journeyId")).longValue());
        assertFalse(persisted.getAttributes().containsKey("mobile.journeyEndedAt"));
        verify(connectionManager).updateDevice(eq(DEVICE_ID), eq(Device.STATUS_ONLINE), any());
    }

    @Test
    public void testHealthyTelemetryDecision() {
        assertTrue(MobileTelemetryApplier.isHealthyTelemetry("wifi", false, 0));
        assertTrue(MobileTelemetryApplier.isHealthyTelemetry("mobile", true, 1999));
        assertTrue(MobileTelemetryApplier.isHealthyTelemetry("mobile", true,
                MobileTelemetryApplier.RTT_BAD_MS - 1));
        assertFalse(MobileTelemetryApplier.isHealthyTelemetry("none", false, 0));
        assertFalse(MobileTelemetryApplier.isHealthyTelemetry(null, false, 0));
        assertTrue(MobileTelemetryApplier.isHealthyTelemetry("wifi", true,
                MobileTelemetryApplier.RTT_BAD_MS));
        assertFalse(MobileTelemetryApplier.isHealthyTelemetry("wifi", true,
                MobileTelemetryApplier.RTT_BAD_MS + 1L));
        assertFalse(MobileTelemetryApplier.isHealthyTelemetry("wifi", true, 5000));
    }

    @Test
    public void testTelemetryApplierPersistsRttSignalAndClearsDegraded() throws Exception {
        Device device = device();
        device.getAttributes().put("mobile.degraded", true);
        JsonNode root = json("{\"payload\":{\"network\":\"wifi\",\"rttMs\":150,\"signal\":3}}");

        new MobileTelemetryApplier(storage).applyTelemetry(device, root);

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(storage, times(1)).updateObject(captor.capture(), any(Request.class));
        Device persisted = captor.getValue();
        assertEquals(150L, ((Number) persisted.getAttributes().get("mobile.rttMs")).longValue());
        assertEquals(3, ((Number) persisted.getAttributes().get("mobile.signal")).intValue());
        assertEquals(Boolean.FALSE, persisted.getAttributes().get("mobile.degraded"));
    }

    @Test
    public void testTelemetryApplierKeepsDegradedWhenRttIsBad() throws Exception {
        Device device = device();
        device.getAttributes().put("mobile.degraded", true);
        JsonNode root = json("{\"payload\":{\"network\":\"mobile\",\"rttMs\":5000,\"signal\":1}}");

        new MobileTelemetryApplier(storage).applyTelemetry(device, root);

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(storage, times(1)).updateObject(captor.capture(), any(Request.class));
        Device persisted = captor.getValue();
        assertEquals(Boolean.TRUE, persisted.getAttributes().get("mobile.degraded"));
    }

    @Test
    public void testTelemetryApplierClearsDegradedWithNetworkOnly() throws Exception {
        Device device = device();
        device.getAttributes().put("mobile.degraded", true);
        // Sin rttMs en el payload: red viva se considera flujo sano.
        JsonNode root = json("{\"payload\":{\"network\":\"mobile\"}}");

        new MobileTelemetryApplier(storage).applyTelemetry(device, root);

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(storage, times(1)).updateObject(captor.capture(), any(Request.class));
        assertEquals(Boolean.FALSE, captor.getValue().getAttributes().get("mobile.degraded"));
    }

    private static Device device() {
        Device device = new Device();
        device.setId(DEVICE_ID);
        device.setUniqueId("device-123");
        return device;
    }

    private static MobileMessage message() {
        MobileMessage message = new MobileMessage();
        message.setDeviceId(DEVICE_ID);
        message.setMessageId("message-123456789");
        message.setSequence(1);
        return message;
    }

    private static MobileEnvelope envelope() {
        MobileEnvelope envelope = new MobileEnvelope();
        envelope.setSchema(1);
        envelope.setType("presence");
        envelope.setMessageId("message-123456789");
        envelope.setDeviceId("device-123");
        envelope.setSequence(1);
        envelope.setSentAt("2026-09-03T12:00:00Z");
        envelope.setObservedAt("2026-09-03T12:00:00Z");
        return envelope;
    }

    private static JsonNode json(String value) throws Exception {
        return new ObjectMapper().readTree(value);
    }
}
