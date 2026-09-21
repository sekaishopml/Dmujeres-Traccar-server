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
package org.traccar.session.state;

/**
 * Motor puro de estado de movimiento V2 (sin dependencias de Storage/Cache).
 * GPS como fuente primaria; velocidad Doppler, velocidad implícita
 * (haversine/dt), calidad del fix y edad del fix como evidencia.
 *
 * Reglas clave:
 * - GPS sin fix NO significa STOPPED.
 * - Un gap largo NO se rellena y NO cuenta como parada: pasa a UNKNOWN.
 * - No crea puntos artificiales ni interpola.
 * - Doppler=0 con desplazamiento real NO declara STOPPED.
 */
public final class MotionStateV2Engine {

    public enum State { UNKNOWN, MOVING, PAUSED, STOPPED }

    public enum QualityClass { GOOD, POOR, INVALID }

    /** Parámetros iniciales; configurables (no constantes fijas). */
    public static class Config {

        private double stopRadiusM = 50;
        private double stopConfirmSeconds = 45;
        private double gapTimeoutSeconds = 300;
        /** Velocidad por debajo de la cual el fix cuenta como sin desplazamiento (m/s). */
        private double stationarySpeedMps = 0.5;

        public Config() {}

        public Config(double stopRadiusM, double stopConfirmSeconds, double gapTimeoutSeconds) {
            this.stopRadiusM = stopRadiusM;
            this.stopConfirmSeconds = stopConfirmSeconds;
            this.gapTimeoutSeconds = gapTimeoutSeconds;
        }

        public double getStopRadiusM() {
            return stopRadiusM;
        }

        public void setStopRadiusM(double stopRadiusM) {
            this.stopRadiusM = stopRadiusM;
        }

        public double getStopConfirmSeconds() {
            return stopConfirmSeconds;
        }

        public void setStopConfirmSeconds(double stopConfirmSeconds) {
            this.stopConfirmSeconds = stopConfirmSeconds;
        }

        public double getGapTimeoutSeconds() {
            return gapTimeoutSeconds;
        }

        public void setGapTimeoutSeconds(double gapTimeoutSeconds) {
            this.gapTimeoutSeconds = gapTimeoutSeconds;
        }

        public double getStationarySpeedMps() {
            return stationarySpeedMps;
        }

        public void setStationarySpeedMps(double stationarySpeedMps) {
            this.stationarySpeedMps = stationarySpeedMps;
        }
    }

    /** Observación de un fix, ya normalizada (el caller extrae del Position). */
    public static class Observation {

        private long timeMs;
        private double latitude;
        private double longitude;
        private double speedMps;
        private double accuracyM;
        private QualityClass quality = QualityClass.INVALID;
        private Double fixAgeSec;
        private boolean valid = true;

        public Observation() {}

        public Observation(long timeMs, double latitude, double longitude,
                double speedMps, double accuracyM, QualityClass quality, Double fixAgeSec) {
            this.timeMs = timeMs;
            this.latitude = latitude;
            this.longitude = longitude;
            this.speedMps = speedMps;
            this.accuracyM = accuracyM;
            this.quality = quality;
            this.fixAgeSec = fixAgeSec;
        }

        public long getTimeMs() {
            return timeMs;
        }

        public void setTimeMs(long timeMs) {
            this.timeMs = timeMs;
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

        public double getSpeedMps() {
            return speedMps;
        }

        public void setSpeedMps(double speedMps) {
            this.speedMps = speedMps;
        }

        public double getAccuracyM() {
            return accuracyM;
        }

        public void setAccuracyM(double accuracyM) {
            this.accuracyM = accuracyM;
        }

        public QualityClass getQuality() {
            return quality;
        }

        public void setQuality(QualityClass quality) {
            this.quality = quality;
        }

        public Double getFixAgeSec() {
            return fixAgeSec;
        }

        public void setFixAgeSec(Double fixAgeSec) {
            this.fixAgeSec = fixAgeSec;
        }

        public boolean isValid() {
            return valid;
        }

        public void setValid(boolean valid) {
            this.valid = valid;
        }
    }

    /** Transición de estado con motivo; null si no hubo cambio. */
    public static class Transition {

        private final State state;
        private final String reason;
        private final long atMs;

        Transition(State state, String reason, long atMs) {
            this.state = state;
            this.reason = reason;
            this.atMs = atMs;
        }

        public State getState() {
            return state;
        }

        public String getReason() {
            return reason;
        }

        public long getAtMs() {
            return atMs;
        }

        @Override
        public String toString() {
            return state + "@" + atMs + " (" + reason + ")";
        }
    }

    private static final double EARTH_RADIUS_M = 6371000.0;

    private final Config config;
    private State state = State.UNKNOWN;
    private String reason = "initial";
    private long stateSinceMs = 0;
    private long pauseStartMs = 0;
    private double anchorLat = 0;
    private double anchorLon = 0;
    private boolean anchorValid = false;
    private long lastFixMs = 0;
    private double lastFixLat = Double.NaN;
    private double lastFixLon = Double.NaN;

    public MotionStateV2Engine(Config config) {
        this.config = config != null ? config : new Config();
    }

    public State getState() {
        return state;
    }

    public String getReason() {
        return reason;
    }

    public long getStateSinceMs() {
        return stateSinceMs;
    }

    public long getPauseStartMs() {
        return pauseStartMs;
    }

    public double getAnchorLatitude() {
        return anchorLat;
    }

    public double getAnchorLongitude() {
        return anchorLon;
    }

    public boolean isAnchorValid() {
        return anchorValid;
    }

    public long getLastFixMs() {
        return lastFixMs;
    }

    public Config getConfig() {
        return config;
    }

    /** Restaura el estado tras un reinicio (persistencia externa). */
    public void restore(State state, String reason, long stateSinceMs,
            long pauseStartMs, double anchorLat, double anchorLon,
            boolean anchorValid, long lastFixMs) {
        this.state = state;
        this.reason = reason;
        this.stateSinceMs = stateSinceMs;
        this.pauseStartMs = pauseStartMs;
        this.anchorLat = anchorLat;
        this.anchorLon = anchorLon;
        this.anchorValid = anchorValid;
        this.lastFixMs = lastFixMs;
        this.lastFixLat = anchorLat;
        this.lastFixLon = anchorLon;
    }

    /** Distancia haversine en metros. */
    public static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * Procesa una observación y actualiza el estado.
     * @return la transición si el estado cambió, null en caso contrario.
     */
    public Transition update(Observation obs) {

        // 1. INVALIDEZ: fix nulo/inválido o coordenadas absurdas → UNKNOWN.
        if (obs == null || !obs.isValid() || obs.getTimeMs() <= 0
                || Double.isNaN(obs.getLatitude()) || Double.isNaN(obs.getLongitude())
                || Math.abs(obs.getLatitude()) > 90 || Math.abs(obs.getLongitude()) > 180
                || (Math.abs(obs.getLatitude()) < 1e-9 && Math.abs(obs.getLongitude()) < 1e-9)) {
            // El tiempo SÍ avanza (continuidad real): un fix inválido no crea
            // un gap fantasma. Las coordenadas NO se adoptan (basura).
            if (obs != null && obs.getTimeMs() > 0) {
                lastFixMs = obs.getTimeMs();
            }
            return transition(State.UNKNOWN, "invalid-fix");
        }

        // 2. GAP: más de gapTimeout sin fixes → UNKNOWN. NO es una parada:
        //    no se rellena ni se retrodata la estabilidad.
        if (lastFixMs > 0 && obs.getTimeMs() - lastFixMs > config.getGapTimeoutSeconds() * 1000) {
            lastFixMs = obs.getTimeMs();
            lastFixLat = obs.getLatitude();
            lastFixLon = obs.getLongitude();
            anchorValid = false;
            pauseStartMs = 0;
            return transition(State.UNKNOWN, "gap>timeout");
        }

        // 3. FUERA DE ORDEN / duplicado: el tiempo no avanza → UNKNOWN.
        if (obs.getTimeMs() <= lastFixMs) {
            return transition(State.UNKNOWN, "out-of-order");
        }

        // 4. CALIDAD insuficiente → UNKNOWN (no se decide sobre fixes malos).
        if (obs.getQuality() != QualityClass.GOOD) {
            lastFixMs = obs.getTimeMs();
            lastFixLat = obs.getLatitude();
            lastFixLon = obs.getLongitude();
            return transition(State.UNKNOWN, "low-quality");
        }

        long previousMs = lastFixMs;
        double previousLat = lastFixLat;
        double previousLon = lastFixLon;
        lastFixMs = obs.getTimeMs();
        lastFixLat = obs.getLatitude();
        lastFixLon = obs.getLongitude();

        double dtSec = previousMs > 0 ? (obs.getTimeMs() - previousMs) / 1000.0 : 0;
        double stepDistance = previousMs > 0
                ? haversine(previousLat, previousLon, obs.getLatitude(), obs.getLongitude()) : Double.NaN;
        // Velocidad implícita GPS (haversine entre fixes consecutivos): evidencia
        // primaria; no depende del Doppler crudo.
        double impliedSpeed = dtSec > 0 && !Double.isNaN(stepDistance)
                ? stepDistance / dtSec : Double.NaN;

        boolean dopplerQuiet = obs.getSpeedMps() < config.getStationarySpeedMps();
        boolean impliedQuiet = Double.isNaN(impliedSpeed) || impliedSpeed < config.getStationarySpeedMps();
        // Coherencia Doppler vs implied: si el Doppler dice quieto pero la
        // distancia demuestra velocidad, manda el GPS (implied).
        boolean evidenceQuiet = dopplerQuiet && impliedQuiet;

        switch (state) {
            case UNKNOWN -> {
                // Re-adquisición tras UNKNOWN: un único fix quieto no basta para
                // STOPPED; se entra en PAUSED y el confirmador decidirá.
                if (evidenceQuiet) {
                    anchorLat = obs.getLatitude();
                    anchorLon = obs.getLongitude();
                    anchorValid = true;
                    pauseStartMs = obs.getTimeMs();
                    return transition(State.PAUSED, "stability-after-unknown");
                }
                anchorValid = false;
                return transition(State.MOVING, "movement-after-unknown");
            }
            case MOVING -> {
                // Estabilidad: Doppler bajo Y desplazamiento real bajo.
                if (evidenceQuiet) {
                    if (anchorValid
                            && haversine(anchorLat, anchorLon, obs.getLatitude(), obs.getLongitude())
                                    <= config.getStopRadiusM()) {
                        // dentro del radio: inicia/continúa ventana de pausa
                        if (pauseStartMs == 0) {
                            pauseStartMs = obs.getTimeMs();
                        }
                        return transition(State.PAUSED, "stability-detected");
                    }
                    anchorLat = obs.getLatitude();
                    anchorLon = obs.getLongitude();
                    anchorValid = true;
                    pauseStartMs = obs.getTimeMs();
                    return transition(State.PAUSED, "stability-detected");
                }
                anchorValid = false;
                pauseStartMs = 0;
                return null;
            }
            case PAUSED -> {
                if (!Double.isNaN(stepDistance) && stepDistance > config.getStopRadiusM()) {
                    anchorValid = false;
                    pauseStartMs = 0;
                    return transition(State.MOVING, "left-stop-radius");
                }
                // Dentro del radio: anclar si aún no hay ancla.
                if (!anchorValid) {
                    anchorLat = obs.getLatitude();
                    anchorLon = obs.getLongitude();
                    anchorValid = true;
                }
                long stableSec = (obs.getTimeMs() - pauseStartMs) / 1000;
                if (stableSec >= config.getStopConfirmSeconds()) {
                    return transition(State.STOPPED, "stop-confirmed");
                }
                return null;
            }
            case STOPPED -> {
                if (!Double.isNaN(stepDistance) && stepDistance > config.getStopRadiusM()) {
                    anchorValid = false;
                    pauseStartMs = 0;
                    return transition(State.MOVING, "left-stop-radius");
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private Transition transition(State next, String why) {
        if (next == state) {
            return null;
        }
        state = next;
        reason = why;
        stateSinceMs = lastFixMs;
        return new Transition(state, reason, stateSinceMs);
    }

}
