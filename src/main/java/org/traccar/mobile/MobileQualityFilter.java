/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.traccar.mobile;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.helper.DistanceCalculator;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Position;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Filtro de calidad DEDICADO al canal móvil (app Android).
 *
 * <p>No toca el bypass del FilterHandler genérico
 * (PositionPipeline.java:157-163 y FilterHandler.java:166-172 quedan intactos):
 * el pipeline genérico sigue saltado para {@code dmj-mqtt} y este filtro es la
 * única defensa server-side del canal móvil.
 *
 * <p>Reglas (ver MobileQualityFilterTest):
 * <ul>
 *   <li>accuracy desconocida ({@code <= 0}, toPosition no llama setAccuracy si es null)
 *       → nunca se rechaza; solo se marca {@code valid=false} si además hay salto de velocidad.</li>
 *   <li>accuracy &gt; reject (default 500 m) → REJECT (absurdo; el móvil borra con rejected).</li>
 *   <li>accuracy en (hide, reject] (default 80-500 m) → {@code valid=false}, se conserva marcado.</li>
 *   <li>velocidad implícita distance(last, position)/dtFixtime &gt; maxSpeedKn (default 140 kn)
 *       con dt &gt; 0 → {@code valid=false}, nunca REJECT (el replay legítimo no se tira).</li>
 *   <li>re-entrega cacheada: coordenadas idénticas a la última fila, speed 0 y dt &lt;=
 *       {@code DUPLICATE_WINDOW_MS} (120 s) → DUPLICATE (no se guarda fila; la app drena
 *       igual porque el ACK duplicate es éxito). Casos reales: el FLP del teléfono
 *       reentrega el mismo fix de red wifi cientos de veces por hora.</li>
 * </ul>
 */
@Singleton
public class MobileQualityFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(MobileQualityFilter.class);

    public enum Verdict { ACCEPT, HIDE, REJECT, DUPLICATE }

    /** Ventana de re-entrega cacheada: misma coordenada exacta + speed 0 en menos de 120 s. */
    public static final long DUPLICATE_WINDOW_MS = 120_000L;

    private final Config config;
    private final Storage storage;

    @Inject
    public MobileQualityFilter(Config config, Storage storage) {
        this.config = config;
        this.storage = storage;
    }

    /**
     * Evaluación pura: no toca Storage, solo decide sobre los dos fixes y muta
     * {@code position.setValid(false)} cuando corresponde conservar marcado.
     * REJECT no muta (no hay fila que conservar).
     */
    public static Verdict assess(
            Position position, Position lastByFixtime,
            double accuracyHide, double accuracyReject, double maxSpeedKn) {
        if (isExactRelay(position, lastByFixtime)) {
            return Verdict.DUPLICATE;
        }
        double accuracy = position.getAccuracy();
        boolean unknown = accuracy <= 0;

        if (!unknown && accuracy > accuracyReject) {
            return Verdict.REJECT;
        }

        boolean hide = false;
        if (!unknown && accuracy > accuracyHide) {
            position.setValid(false);
            hide = true;
        }

        if (lastByFixtime != null
                && position.getFixTime() != null
                && lastByFixtime.getFixTime() != null) {
            long dtMs = position.getFixTime().getTime() - lastByFixtime.getFixTime().getTime();
            if (dtMs > 0) {
                double distance = DistanceCalculator.distance(lastByFixtime, position);
                double speedKn = UnitsConverter.knotsFromMps(distance / (dtMs / 1000.0));
                if (speedKn > maxSpeedKn) {
                    position.setValid(false);
                    hide = true;
                }
            }
        }

        return hide ? Verdict.HIDE : Verdict.ACCEPT;
    }

    /**
     * Re-entrega cacheada del mismo fix (FLP de red sin GNSS): coordenadas bit a bit
     * iguales a la última fila guardada, speed 0 en ambas y fixtime dentro de la
     * ventana. Un receptor GPS real nunca repite la coordenada al centímetro.
     */
    public static boolean isExactRelay(Position position, Position lastByFixtime) {
        if (lastByFixtime == null || position.getFixTime() == null
                || lastByFixtime.getFixTime() == null) {
            return false;
        }
        long dtMs = position.getFixTime().getTime() - lastByFixtime.getFixTime().getTime();
        return dtMs >= 0 && dtMs <= DUPLICATE_WINDOW_MS
                && position.getSpeed() == 0.0 && lastByFixtime.getSpeed() == 0.0
                && Double.compare(position.getLatitude(), lastByFixtime.getLatitude()) == 0
                && Double.compare(position.getLongitude(), lastByFixtime.getLongitude()) == 0;
    }

    /** Última posición con coordenadas DISTINTAS a la anterior por dispositivo. */
    private final ConcurrentHashMap<Long, Long> lastMovementAt = new ConcurrentHashMap<>();

    /** Movimiento real reciente o línea base si el server aún no vio ninguna. */
    public long lastMovementMs(long deviceId) {
        return lastMovementAt.computeIfAbsent(deviceId, key -> System.currentTimeMillis());
    }

    private void recordMovement(Position position, Position lastByFixtime) {
        if (lastByFixtime == null
                || Double.compare(position.getLatitude(), lastByFixtime.getLatitude()) != 0
                || Double.compare(position.getLongitude(), lastByFixtime.getLongitude()) != 0) {
            lastMovementAt.put(position.getDeviceId(), System.currentTimeMillis());
        }
    }

    /**
     * Última posición del device ordenada por fixtime desc (una lectura indexada
     * por position_deviceid_fixtime). Null si no hay previa o si Storage falla
     * (fail-open: solo se aplican umbrales absolutos).
     */
    public Position fetchLast(long deviceId) {
        try {
            return storage.getObject(Position.class, new Request(
                    new Columns.All(),
                    new Condition.Equals("deviceId", deviceId),
                    new Order("fixTime", true, 1)));
        } catch (Exception error) {
            LOGGER.warn("Mobile quality filter: failed to fetch last position", error);
            return null;
        }
    }

    /**
     * Aplica el filtro con los umbrales configurados
     * ({@code mobile.filter.accuracyHide / accuracyReject / maxSpeedKn}).
     */
    public Verdict apply(Position position) {
        double hide = config.getDouble(Keys.MOBILE_FILTER_ACCURACY_HIDE);
        double reject = config.getDouble(Keys.MOBILE_FILTER_ACCURACY_REJECT);
        double maxSpeed = config.getDouble(Keys.MOBILE_FILTER_MAX_SPEED_KN);
        Position last = fetchLast(position.getDeviceId());
        Verdict verdict = assess(position, last, hide, reject, maxSpeed);
        // HIDE también cuenta: es fila guardada (fix marcado) con coords posiblemente nuevas.
        if (verdict == Verdict.ACCEPT || verdict == Verdict.HIDE) {
            recordMovement(position, last);
        }
        return verdict;
    }
}
