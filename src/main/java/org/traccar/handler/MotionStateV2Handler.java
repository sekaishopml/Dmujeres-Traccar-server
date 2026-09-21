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
package org.traccar.handler;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.session.state.MotionStateV2Engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Estado de movimiento V2 (MOVING/PAUSED/STOPPED/UNKNOWN) calculado por el
 * servidor con MotionStateV2Engine (GPS primario: haversine/dt + Doppler +
 * calidad). Solo canal móvil ({@code dmj-mqtt}); los trackers legacy no se
 * tocan. Añade el atributo "motionStateV2" a la posición: NO genera eventos,
 * NO toca KEY_MOTION ni los eventos deviceMoving/deviceStopped upstream
 * (compatibilidad Traccar intacta).
 */
@Singleton
public class MotionStateV2Handler extends BasePositionHandler {

    public static final String KEY_MOTION_STATE_V2 = "motionStateV2";

    private final CacheManager cacheManager;
    private final Map<Long, MotionStateV2Engine> engines = new ConcurrentHashMap<>();

    @Inject
    public MotionStateV2Handler(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        if (!"dmj-mqtt".equals(position.getProtocol())) {
            callback.processed(false);
            return;
        }
        MotionStateV2Engine engine = engines.computeIfAbsent(
                position.getDeviceId(), id -> seed(id));
        MotionStateV2Engine.Observation observation = toObservation(position);
        engine.update(observation);
        position.set(KEY_MOTION_STATE_V2, engine.getState().name());
        callback.processed(false);
    }

    /** Seed desde la última posición persistida (reinicio conservando estado). */
    private MotionStateV2Engine seed(long deviceId) {
        MotionStateV2Engine engine = new MotionStateV2Engine(new MotionStateV2Engine.Config());
        try {
            Position last = cacheManager.getPosition(deviceId);
            if (last != null) {
                String stored = last.getString(KEY_MOTION_STATE_V2);
                MotionStateV2Engine.State state = stored == null
                        ? MotionStateV2Engine.State.UNKNOWN
                        : MotionStateV2Engine.State.valueOf(stored);
                long fixMs = last.getFixTime() != null ? last.getFixTime().getTime() : 0;
                engine.restore(state, "restored", fixMs, fixMs,
                        last.getLatitude(), last.getLongitude(), true, fixMs);
            }
        } catch (RuntimeException ignored) {
            // fail-open: arranca UNKNOWN
        }
        return engine;
    }

    public static MotionStateV2Engine.Observation toObservation(Position position) {
        MotionStateV2Engine.Observation observation = new MotionStateV2Engine.Observation();
        observation.setTimeMs(position.getFixTime() != null ? position.getFixTime().getTime() : 0);
        observation.setLatitude(position.getLatitude());
        observation.setLongitude(position.getLongitude());
        observation.setSpeedMps(position.getSpeed() * 0.514444); // kn → m/s
        observation.setAccuracyM(position.getAccuracy());
        String quality = position.getString("qualityClass");
        observation.setQuality("EXCELLENT".equals(quality) || "GOOD".equals(quality)
                ? MotionStateV2Engine.QualityClass.GOOD
                : "POOR".equals(quality) || "FAIR".equals(quality)
                ? MotionStateV2Engine.QualityClass.POOR
                : MotionStateV2Engine.QualityClass.INVALID);
        Long fixAge = position.getLong("fixAgeSec");
        observation.setFixAgeSec(fixAge > 0 ? fixAge.doubleValue() : null);
        observation.setValid(position.getValid());
        return observation;
    }

}
