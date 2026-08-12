/* Copyright 2026 Anton Tananaev (anton@traccar.org) */
package org.traccar.mobile;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class MobileMqttConsumerTest {

    @Test
    public void testResolveTopicDeviceId() {
        assertEquals("device-123", MobileMqttConsumer.resolveTopicDeviceId(
                "dmj/v1/devices/device-123/telemetry", "dmj/v1/devices/+/telemetry"));
    }

    @Test
    public void testRejectsUnexpectedTopic() {
        assertThrows(IllegalArgumentException.class, () -> MobileMqttConsumer.resolveTopicDeviceId(
                "dmj/v1/devices/device-123/other", "dmj/v1/devices/+/telemetry"));
    }

    @Test
    public void testPositionConversionUsesKnots() {
        MobileEnvelope envelope = new MobileEnvelope();
        envelope.setObservedAt("2026-08-12T12:00:00Z");
        MobileEnvelope.Payload payload = new MobileEnvelope.Payload();
        payload.setLatitude(1);
        payload.setLongitude(2);
        payload.setSpeed(36.0);
        envelope.setPayload(payload);
        assertEquals(19.438452, MobileIngestionService.toPosition(envelope, 7).getSpeed(), 0.000001);
    }
}
