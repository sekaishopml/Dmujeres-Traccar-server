/* Copyright 2026 Anton Tananaev (anton@traccar.org) */
package org.traccar.mobile;

import org.junit.jupiter.api.Test;
import org.traccar.model.Position;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas puras del orquestador de ingestión móvil (sin DI ni base de datos).
 * Cubren las funciones estáticas que se quedaron en MobileIngestionService
 * tras extraer MobileTelemetryApplier y MobilePresenceService.
 */
public class MobileIngestionServiceTest {

    @Test
    public void testCanonicalHashIsStable() {
        MobileEnvelope first = envelope("msg-1", 7);
        MobileEnvelope second = envelope("msg-1", 7);
        assertEquals(MobileIngestionService.canonicalHash(first), MobileIngestionService.canonicalHash(second));
    }

    @Test
    public void testCanonicalHashChangesWithMessageId() {
        assertNotEquals(
                MobileIngestionService.canonicalHash(envelope("msg-1", 7)),
                MobileIngestionService.canonicalHash(envelope("msg-2", 7)));
    }

    @Test
    public void testToPositionKeepsFixTimeAndMapsFields() {
        MobileEnvelope envelope = envelope("msg-9", 3);
        Position position = MobileIngestionService.toPosition(envelope, 42L);
        assertEquals(42L, position.getDeviceId());
        assertEquals(-33.45, position.getLatitude(), 0.000001);
        assertEquals(-70.67, position.getLongitude(), 0.000001);
        assertTrue(position.getValid());
        // La hora del fix se conserva tal cual (clock skew no destructivo).
        assertEquals("2026-08-12T12:00:00Z", envelope.getObservedAt());
    }

    @Test
    public void testToPositionMapsQualityAttributes() {
        MobileEnvelope envelope = envelope("msg-10", 4);
        MobileEnvelope.Payload payload = envelope.getPayload();
        payload.setSpeedAccuracyMps(1.5f);
        payload.setBearingAccuracyDeg(10.0f);
        payload.setAltitudeAccuracyM(12.5);
        payload.setConfidence(90);
        payload.setGnssUsed(8);
        payload.setGnssTotal(12);
        payload.setSessionId("session-abc");
        payload.setBootId("boot-xyz");

        Position position = MobileIngestionService.toPosition(envelope, 42L);

        assertEquals(1.5, (Double) position.getAttributes().get("speedAccuracyMps"), 0.000001);
        assertEquals(10.0, (Double) position.getAttributes().get("bearingAccuracyDeg"), 0.000001);
        assertEquals(12.5, (Double) position.getAttributes().get("altitudeAccuracyM"), 0.000001);
        assertEquals(90, ((Number) position.getAttributes().get("fixConfidence")).intValue());
        assertEquals(8, ((Number) position.getAttributes().get("gnssUsed")).intValue());
        assertEquals(12, ((Number) position.getAttributes().get("gnssTotal")).intValue());
        assertEquals("session-abc", position.getAttributes().get("mobile.sessionId"));
        assertEquals("boot-xyz", position.getAttributes().get("mobile.bootId"));
    }

    private static MobileEnvelope envelope(String messageId, long sequence) {
        MobileEnvelope envelope = new MobileEnvelope();
        envelope.setSchema(1);
        envelope.setType("position");
        envelope.setMessageId(messageId);
        envelope.setDeviceId("device-123");
        envelope.setSequence(sequence);
        envelope.setSentAt("2026-08-12T12:00:00Z");
        envelope.setObservedAt("2026-08-12T12:00:00Z");
        MobileEnvelope.Payload payload = new MobileEnvelope.Payload();
        payload.setLatitude(-33.45);
        payload.setLongitude(-70.67);
        payload.setAccuracy(8.2);
        payload.setSpeed(36.0);
        payload.setBearing(180.0);
        payload.setAltitude(520.0);
        envelope.setPayload(payload);
        return envelope;
    }
}
