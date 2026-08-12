/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.mobile;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class MobileEnvelopeValidatorTest {

    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");

    @Test
    public void testValidEnvelope() {
        assertDoesNotThrow(() -> MobileEnvelopeValidator.validate(valid(), "device-123", NOW));
    }

    @Test
    public void testRejectsTopicMismatch() {
        assertThrows(IllegalArgumentException.class,
                () -> MobileEnvelopeValidator.validate(valid(), "other-device", NOW));
    }

    @Test
    public void testRejectsInvalidCoordinates() {
        MobileEnvelope envelope = valid();
        envelope.getPayload().setLatitude(91);
        assertThrows(IllegalArgumentException.class,
                () -> MobileEnvelopeValidator.validate(envelope, "device-123", NOW));
    }

    @Test
    public void testRejectsExpiredPosition() {
        MobileEnvelope envelope = valid();
        envelope.setObservedAt(Instant.parse("2026-08-01T11:59:59Z"));
        assertThrows(IllegalArgumentException.class,
                () -> MobileEnvelopeValidator.validate(envelope, "device-123", NOW));
    }

    private static MobileEnvelope valid() {
        MobileEnvelope envelope = new MobileEnvelope();
        envelope.setSchema(1);
        envelope.setType("position");
        envelope.setMessageId("01J00000000000000000000000");
        envelope.setDeviceId("device-123");
        envelope.setSequence(1);
        envelope.setSentAt(NOW);
        envelope.setObservedAt(NOW);
        MobileEnvelope.Payload payload = new MobileEnvelope.Payload();
        payload.setLatitude(-33.45);
        payload.setLongitude(-70.67);
        payload.setAccuracy(8.2);
        payload.setSpeed(12.4);
        payload.setBearing(180.0);
        payload.setAltitude(520.0);
        envelope.setPayload(payload);
        return envelope;
    }
}
