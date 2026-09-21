/*
 * Copyright 2017 - 2026 Anton Tananaev (anton@traccar.org)
 * Copyright 2017 Andrey Kunitsyn (andrey@traccar.org)
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
import org.traccar.config.Keys;
import org.traccar.helper.model.AttributeUtil;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

/**
 * Marca KEY_MOTION quality-aware: no declara STOPPED solo porque el Doppler
 * sea 0. Si speedSource=implied o desconocido, la velocidad implícita
 * (distancia/dt, atributo KEY_DISTANCE ya calculado por DistanceHandler) es
 * evidencia de movimiento; Doppler=0 con desplazamiento real NO es parado.
 * El estado del acelerómetro (motionState) es auxiliar y nunca autoridad.
 */
public class MotionHandler extends BasePositionHandler {

    /** Umbral de velocidad implícita (kn) por debajo del cual se considera quieto. */
    private static final double IMPLIED_STATIONARY_KN = 0.5;

    private final CacheManager cacheManager;

    @Inject
    public MotionHandler(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Override
    public void onPosition(Position position, Callback callback) {
        if (!position.hasAttribute(Position.KEY_MOTION)) {
            double threshold = AttributeUtil.lookup(
                    cacheManager, Keys.EVENT_MOTION_SPEED_THRESHOLD, position.getDeviceId());
            boolean dopplerMoving = position.getSpeed() > threshold;

            String speedSource = position.getString("speedSource");
            boolean dopplerTrusted = "doppler".equals(speedSource);

            boolean moving;
            if (dopplerMoving) {
                // Doppler dice movimiento: aceptar (GPS primario).
                moving = true;
            } else if (dopplerTrusted) {
                // Doppler fiable y quieto: solo entonces es evidencia de parada.
                moving = false;
            } else {
                // Doppler 0/unknown: la velocidad implícita manda si existe.
                // KEY_DISTANCE ya lo calculó DistanceHandler (last→current).
                double distance = position.getDouble(Position.KEY_DISTANCE);
                long dtMs = 0;
                Position last = cacheManager.getPosition(position.getDeviceId());
                if (position.getFixTime() != null && last != null && last.getFixTime() != null) {
                    dtMs = position.getFixTime().getTime() - last.getFixTime().getTime();
                }
                double impliedKn = dtMs > 0
                        ? org.traccar.helper.UnitsConverter.knotsFromMps(distance / (dtMs / 1000.0))
                        : Double.NaN;
                // Con dt fiable: implied > umbral quieto. Sin dt: cualquier
                // distancia positiva entre fixes ya es evidencia de movimiento.
                moving = Double.isNaN(impliedKn)
                        ? distance > 0 : impliedKn > IMPLIED_STATIONARY_KN;
            }
            position.set(Position.KEY_MOTION, moving);
        }
        callback.processed(false);
    }

}
