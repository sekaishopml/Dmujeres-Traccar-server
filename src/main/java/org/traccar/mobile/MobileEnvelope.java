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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MobileEnvelope {

    private int schema;
    private String type;
    private String messageId;
    private String deviceId;
    private long sequence;
    private String sentAt;
    private String observedAt;
    private Payload payload;

    public int getSchema() {
        return schema;
    }

    public void setSchema(int schema) {
        this.schema = schema;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public long getSequence() {
        return sequence;
    }

    public void setSequence(long sequence) {
        this.sequence = sequence;
    }

    public String getSentAt() {
        return sentAt;
    }

    public void setSentAt(String sentAt) {
        this.sentAt = sentAt;
    }

    public String getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(String observedAt) {
        this.observedAt = observedAt;
    }

    public Payload getPayload() {
        return payload;
    }

    public void setPayload(Payload payload) {
        this.payload = payload;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Payload {

        private double latitude;
        private double longitude;
        private Double accuracy;
        private Double speed;
        private Double bearing;
        private Double altitude;
        private String provider;
        private Long fixAgeSec;
        @JsonProperty("sessionId")
        private String sessionId;
        @JsonProperty("bootId")
        private String bootId;
        @JsonProperty("speedAccuracyMps")
        private Float speedAccuracyMps;
        @JsonProperty("speedSource")
        private String speedSource;
        @JsonProperty("qualityClass")
        private String qualityClass;
        @JsonProperty("bearingAccuracyDeg")
        private Float bearingAccuracyDeg;
        @JsonProperty("altitudeAccuracyM")
        private Double altitudeAccuracyM;
        @JsonProperty("confidence")
        private Integer confidence;
        @JsonProperty("gnssUsed")
        private Integer gnssUsed;
        @JsonProperty("gnssTotal")
        private Integer gnssTotal;

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public String getBootId() {
            return bootId;
        }

        public void setBootId(String bootId) {
            this.bootId = bootId;
        }

        public Float getSpeedAccuracyMps() {
            return speedAccuracyMps;
        }

        public void setSpeedAccuracyMps(Float speedAccuracyMps) {
            this.speedAccuracyMps = speedAccuracyMps;
        }

        public String getSpeedSource() {
            return speedSource;
        }

        public void setSpeedSource(String speedSource) {
            this.speedSource = speedSource;
        }

        public String getQualityClass() {
            return qualityClass;
        }

        public void setQualityClass(String qualityClass) {
            this.qualityClass = qualityClass;
        }

        public Float getBearingAccuracyDeg() {
            return bearingAccuracyDeg;
        }

        public void setBearingAccuracyDeg(Float bearingAccuracyDeg) {
            this.bearingAccuracyDeg = bearingAccuracyDeg;
        }

        public Double getAltitudeAccuracyM() {
            return altitudeAccuracyM;
        }

        public void setAltitudeAccuracyM(Double altitudeAccuracyM) {
            this.altitudeAccuracyM = altitudeAccuracyM;
        }

        public Integer getConfidence() {
            return confidence;
        }

        public void setConfidence(Integer confidence) {
            this.confidence = confidence;
        }

        public Integer getGnssUsed() {
            return gnssUsed;
        }

        public void setGnssUsed(Integer gnssUsed) {
            this.gnssUsed = gnssUsed;
        }

        public Integer getGnssTotal() {
            return gnssTotal;
        }

        public void setGnssTotal(Integer gnssTotal) {
            this.gnssTotal = gnssTotal;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public Long getFixAgeSec() {
            return fixAgeSec;
        }

        public void setFixAgeSec(Long fixAgeSec) {
            this.fixAgeSec = fixAgeSec;
        }

        public double getLatitude() {
            return latitude;
        }

        public void setLatitude(double latitude) {
            this.latitude = latitude;
        }

        public double getLongitude() {
            return longitude;
        }

        public void setLongitude(double longitude) {
            this.longitude = longitude;
        }

        public Double getAccuracy() {
            return accuracy;
        }

        public void setAccuracy(Double accuracy) {
            this.accuracy = accuracy;
        }

        public Double getSpeed() {
            return speed;
        }

        public void setSpeed(Double speed) {
            this.speed = speed;
        }

        public Double getBearing() {
            return bearing;
        }

        public void setBearing(Double bearing) {
            this.bearing = bearing;
        }

        public Double getAltitude() {
            return altitude;
        }

        public void setAltitude(Double altitude) {
            this.altitude = altitude;
        }
    }
}
