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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

public final class MobileEnvelopeValidator {

    private static final Pattern MESSAGE_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{16,64}");
    private static final Pattern DEVICE_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final long MAX_FUTURE_SECONDS = 300;
    private static final long MAX_AGE_DAYS = 7;

    private MobileEnvelopeValidator() {
    }

    public static void validate(MobileEnvelope envelope, String topicDeviceId, Instant now) {
        require(envelope != null, "envelope is required");
        require(envelope.getSchema() == 1, "unsupported schema");
        require("position".equals(envelope.getType()), "unsupported type");
        require(envelope.getMessageId() != null
                && MESSAGE_ID_PATTERN.matcher(envelope.getMessageId()).matches(), "invalid messageId");
        require(envelope.getDeviceId() != null
                && DEVICE_ID_PATTERN.matcher(envelope.getDeviceId()).matches(), "invalid deviceId");
        require(envelope.getDeviceId().equals(topicDeviceId), "topic and envelope deviceId differ");
        require(envelope.getSequence() > 0, "sequence must be positive");
        require(envelope.getSentAt() != null, "sentAt is required");
        require(envelope.getObservedAt() != null, "observedAt is required");
        require(envelope.getPayload() != null, "payload is required");

        Instant observedAt;
        Instant sentAt;
        try {
            observedAt = Instant.parse(envelope.getObservedAt());
            sentAt = Instant.parse(envelope.getSentAt());
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid timestamp", error);
        }
        Instant oldest = now.minus(MAX_AGE_DAYS, ChronoUnit.DAYS);
        Instant newest = now.plus(MAX_FUTURE_SECONDS, ChronoUnit.SECONDS);
        require(!observedAt.isBefore(oldest), "observedAt is expired");
        require(!sentAt.isAfter(newest), "sentAt is in the future");

        MobileEnvelope.Payload payload = envelope.getPayload();
        require(Double.isFinite(payload.getLatitude())
                && payload.getLatitude() >= -90 && payload.getLatitude() <= 90, "invalid latitude");
        require(Double.isFinite(payload.getLongitude())
                && payload.getLongitude() >= -180 && payload.getLongitude() <= 180, "invalid longitude");
        requireNonNegative(payload.getAccuracy(), "invalid accuracy");
        requireNonNegative(payload.getSpeed(), "invalid speed");
        requireRange(payload.getBearing(), 0, 360, "invalid bearing");
    }

    private static void requireNonNegative(Double value, String message) {
        require(value == null || Double.isFinite(value) && value >= 0, message);
    }

    private static void requireRange(Double value, double min, double max, String message) {
        require(value == null || Double.isFinite(value) && value >= min && value <= max, message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
