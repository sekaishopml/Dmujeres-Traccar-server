/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.traccar.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import org.json.JSONArray;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.database.DeviceLookupService;
import org.traccar.database.NotificationManager;
import org.traccar.handler.PositionPersistenceHandler;
import org.traccar.handler.PositionPipeline;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Device;
import org.traccar.model.MobileMessage;
import org.traccar.model.Position;
import org.traccar.session.ConnectionManager;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Shared ingestion pipeline for the mobile channel. Both the MQTT consumer and the HTTP
 * fallback use the same validation, idempotent reservation, lease and atomic persistence,
 * so a position submitted over either transport is deduplicated identically.
 */
@Singleton
public class MobileIngestionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MobileIngestionService.class);

    public enum AckStatus { ACCEPTED, DUPLICATE, REJECTED, INVALID, EXPIRED, PENDING }

    /**
     * El reloj del dispositivo móvil puede estar desfasado (horas). Si la hora que reporta
     * se aleja más de este umbral de la hora real del servidor, se usa la hora del servidor
     * para que las posiciones queden fechadas correctamente y el replay / estado funcionen.
     */
    private static final long CLOCK_SKEW_THRESHOLD_MS = 60L * 60L * 1000L;

    public record Result(AckStatus status, MobileEnvelope envelope) {}

    private final Config config;
    private final ObjectMapper mapper;
    private final DeviceLookupService devices;
    private final MobileMessageStore messages;
    private final MobileAtomicPersistence atomic;
    private final PositionPipeline pipeline;
    private final CacheManager cacheManager;
    private final ConnectionManager connectionManager;
    private final Storage storage;
    private final NotificationManager notificationManager;
    private final MobileJourneyRegistry journeyRegistry;

    @Inject
    public MobileIngestionService(Config config, ObjectMapper mapper, DeviceLookupService devices,
            MobileMessageStore messages, MobileAtomicPersistence atomic,
            PositionPipeline pipeline, CacheManager cacheManager, ConnectionManager connectionManager,
            Storage storage, NotificationManager notificationManager,
            MobileJourneyRegistry journeyRegistry) {
        this.config = config;
        this.mapper = mapper;
        this.devices = devices;
        this.messages = messages;
        this.atomic = atomic;
        this.pipeline = pipeline;
        this.cacheManager = cacheManager;
        this.connectionManager = connectionManager;
        this.storage = storage;
        this.notificationManager = notificationManager;
        this.journeyRegistry = journeyRegistry;
    }

    public CompletionStage<Result> process(byte[] payload, String topicDeviceId) {
        MobileEnvelope envelope = null;
        try {
            envelope = mapper.readValue(payload, MobileEnvelope.class);
            final MobileEnvelope captured = envelope;
            final JsonNode root = mapper.readTree(payload);
            MobileEnvelopeValidator.validate(captured, topicDeviceId, Instant.now());
            Device device = devices.lookup(new String[] {topicDeviceId});
            if (device == null) {
                return CompletableFuture.completedFuture(new Result(AckStatus.REJECTED, captured));
            }

            MobileMessageStore.Result reservation = messages.reserve(
                    device.getId(), captured, canonicalHash(captured));
            if (reservation.reservation() == MobileMessageStore.Reservation.DUPLICATE) {
                return CompletableFuture.completedFuture(new Result(AckStatus.DUPLICATE, captured));
            }
            if (reservation.reservation() == MobileMessageStore.Reservation.REJECTED) {
                return CompletableFuture.completedFuture(new Result(AckStatus.REJECTED, captured));
            }

            MobileMessage message = reservation.message();
            long leaseMs = config.getInteger(Keys.MOBILE_MQTT_LEASE_SECONDS) * 1000L;
            if (atomic.claim(message, leaseMs) == null) {
                return CompletableFuture.completedFuture(new Result(AckStatus.PENDING, captured));
            }

            if ("presence".equals(captured.getType())) {
                // Heartbeat o señal de inicio/fin de jornada: cambia el estado en tiempo real
                // y actualiza telemetría SIN persistir posiciones ficticias.
                try {
                    messages.completeWithoutPosition(message);
                    MobileTelemetryMonitor.TelemetrySnapshot before =
                            MobileTelemetryMonitor.capture(device);
                    applyTelemetry(device, root);
                    JsonNode presence = root.path("payload");
                    boolean started = presence.hasNonNull("journeyStarted")
                            && presence.get("journeyStarted").asBoolean();
                    boolean ended = presence.hasNonNull("journeyEnded")
                            && presence.get("journeyEnded").asBoolean();
                    if (ended) {
                        journeyRegistry.end(device.getId());
                        device.getAttributes().put("mobile.journeyId", 0L);
                    } else if (started) {
                        long journeyId = presence.hasNonNull("journeyId")
                                ? presence.get("journeyId").asLong() : 0L;
                        journeyRegistry.start(device.getId(), journeyId);
                    }
                    connectionManager.updateDevice(device.getId(),
                            ended ? Device.STATUS_OFFLINE : Device.STATUS_ONLINE, new Date());
                    connectionManager.updateDevice(true, device);
                    // Detectar cambios de telemetría y crear eventos (GPS off, red, batería).
                    try {
                        var events = MobileTelemetryMonitor.detectChanges(device, before, root);
                        for (var event : events) {
                            notificationManager.updateEvents(
                                    java.util.Collections.singletonMap(event, null));
                        }
                    } catch (Exception eventsError) {
                        LOGGER.warn("Failed to detect telemetry events", eventsError);
                    }
                    return CompletableFuture.completedFuture(new Result(AckStatus.ACCEPTED, captured));
                } catch (Exception error) {
                    LOGGER.warn("Failed to finalize presence heartbeat", error);
                    return CompletableFuture.completedFuture(new Result(AckStatus.PENDING, captured));
                }
            }

            Position position = toPosition(captured, device.getId());
            JsonNode positionTelemetry = root.path("payload");
            if (positionTelemetry.hasNonNull("battery")) {
                position.set("batteryLevel", positionTelemetry.get("battery").asInt());
            }
            if (positionTelemetry.hasNonNull("network")) {
                position.set("network", positionTelemetry.get("network").asText());
            }
            String cacheKey = "mobile:" + captured.getMessageId();
            cacheManager.addDevice(device.getId(), cacheKey);
            PositionPersistenceHandler atomicHandler = new PositionPersistenceHandler() {
                @Override
                public CompletionStage<Boolean> persist(Position value) {
                    return CompletableFuture.supplyAsync(() -> atomic.persist(message, value));
                }
            };
            PositionPipeline.Executor pipelineExecutor = new PositionPipeline.Executor() {
                @Override
                public boolean inEventLoop() {
                    return true;
                }

                @Override
                public void execute(Runnable command) {
                    command.run();
                }
            };
            return pipeline.process(position, pipelineExecutor, atomicHandler)
                    .whenComplete((ignored, error) -> cacheManager.removeDevice(device.getId(), cacheKey))
                    .thenApply(result2 -> {
                        if (result2.persisted() || result2.filtered()) {
                            // La telemetría (batería, red, estado) se aplica tanto si la
                            // posición se guarda como si se filtra por duplicada/cercana:
                            // si el dispositivo está quieto manda posiciones casi idénticas
                            // que el servidor filtra, y de lo contrario la batería y el
                            // estado "en línea" se congelarían en el panel.
                            try {
                                applyTelemetry(device, root);
                            } catch (Exception telemetryError) {
                                LOGGER.warn("Failed to apply mobile telemetry", telemetryError);
                            }
                            JsonNode telemetryPayload = root.path("payload");
                            if (telemetryPayload.hasNonNull("journeyId")) {
                                journeyRegistry.start(device.getId(),
                                        telemetryPayload.get("journeyId").asLong());
                            }
                            connectionManager.updateDevice(device.getId(), Device.STATUS_ONLINE, new Date());
                            connectionManager.updateDevice(true, device);
                            try {
                                messages.completeWithoutPosition(message);
                            } catch (Exception completionError) {
                                LOGGER.error("Failed to finalize filtered mobile message", completionError);
                                return new Result(AckStatus.PENDING, captured);
                            }
                            return new Result(AckStatus.ACCEPTED, captured);
                        }
                        return new Result(AckStatus.PENDING, captured);
                    })
                    .exceptionally(error -> {
                        LOGGER.error("Mobile atomic processing failed; leaving for retry", error);
                        return new Result(AckStatus.PENDING, captured);
                    });
        } catch (Exception error) {
            LOGGER.warn("Invalid mobile message", error);
            if (envelope != null) {
                AckStatus status = error.getMessage() != null && error.getMessage().contains("expired")
                        ? AckStatus.EXPIRED : AckStatus.INVALID;
                return CompletableFuture.completedFuture(new Result(status, envelope));
            }
            return CompletableFuture.completedFuture(new Result(AckStatus.INVALID, null));
        }
    }

    /** Actualiza atributos de telemetría del dispositivo sin borrar los existentes. */
    private void applyTelemetry(Device device, JsonNode root) throws Exception {
        if (root == null || !root.hasNonNull("payload")) {
            return;
        }
        JsonNode telemetry = root.path("payload");
        if (telemetry.hasNonNull("pending")) {
            device.getAttributes().put("mobile.pending", telemetry.get("pending").asLong());
        }
        if (telemetry.hasNonNull("battery")) {
            int battery = telemetry.get("battery").asInt();
            device.getAttributes().put("mobile.battery", battery);
            Object existing = device.getAttributes().get("mobile.batteryHistory");
            JSONArray history;
            if (existing instanceof String && !((String) existing).isBlank()) {
                try {
                    history = new JSONArray((String) existing);
                } catch (Exception error) {
                    history = new JSONArray();
                }
            } else {
                history = new JSONArray();
            }
            long nowSeconds = System.currentTimeMillis() / 1000;
            if (history.length() > 0) {
                JSONArray last = history.optJSONArray(history.length() - 1);
                if (last != null && nowSeconds - last.optLong(0) < 60) {
                    history.remove(history.length() - 1);
                }
            }
            JSONArray sample = new JSONArray();
            sample.put(nowSeconds);
            sample.put(battery);
            history.put(sample);
            while (history.length() > 100) {
                history.remove(0);
            }
            device.getAttributes().put("mobile.batteryHistory", history.toString());
        }
        if (telemetry.hasNonNull("network")) {
            device.getAttributes().put("mobile.network", telemetry.get("network").asText());
        }
        if (telemetry.hasNonNull("vendor")) {
            device.getAttributes().put("mobile.vendor", telemetry.get("vendor").asText());
        }
        if (telemetry.hasNonNull("model")) {
            device.getAttributes().put("mobile.model", telemetry.get("model").asText());
        }
        if (telemetry.hasNonNull("appVersion")) {
            device.getAttributes().put("mobile.appVersion", telemetry.get("appVersion").asText());
        }
        if (telemetry.hasNonNull("gps")) {
            device.getAttributes().put("mobile.gps", telemetry.get("gps").asText());
        }
        if (telemetry.hasNonNull("journeyId")) {
            device.getAttributes().put("mobile.journeyId", telemetry.get("journeyId").asLong());
        }
        storage.updateObject(device, new Request(
                new Columns.Include("attributes"), new Condition.Equals("id", device.getId())));
    }

    public static Position toPosition(MobileEnvelope envelope, long deviceId) {
        MobileEnvelope.Payload value = envelope.getPayload();
        Position position = new Position("dmj-mqtt");
        position.setDeviceId(deviceId);
        position.setTime(trustedDeviceTime(envelope));
        position.setLatitude(value.getLatitude());
        position.setLongitude(value.getLongitude());
        if (value.getAccuracy() != null) {
            position.setAccuracy(value.getAccuracy());
        }
        if (value.getAltitude() != null) {
            position.setAltitude(value.getAltitude());
        }
        if (value.getBearing() != null) {
            position.setCourse(value.getBearing());
        }
        if (value.getSpeed() != null) {
            position.setSpeed(UnitsConverter.knotsFromKph(value.getSpeed()));
        }
        position.setValid(true);
        return position;
    }

    /**
     * Canonical hash over the logical envelope fields in contract order. Independent of the
     * transport (MQTT raw bytes vs HTTP JSON) so the same message is deduplicated identically
     * across channels.
     */
    public static String canonicalHash(MobileEnvelope envelope) {
        MobileEnvelope.Payload payload = envelope.getPayload();
        StringBuilder value = new StringBuilder();
        value.append(envelope.getSchema()).append('|').append(envelope.getType()).append('|')
                .append(envelope.getMessageId()).append('|').append(envelope.getDeviceId()).append('|')
                .append(envelope.getSequence()).append('|').append(envelope.getSentAt()).append('|')
                .append(envelope.getObservedAt()).append('|');
        if (payload != null) {
            value.append(payload.getLatitude()).append('|').append(payload.getLongitude()).append('|')
                    .append(payload.getAccuracy()).append('|').append(payload.getSpeed()).append('|')
                    .append(payload.getBearing()).append('|').append(payload.getAltitude());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes());
            StringBuilder result = new StringBuilder(64);
            for (byte element : digest) {
                result.append("%02x".formatted(element & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    /**
     * Devuelve la hora de la posición confiando en el servidor cuando el reloj del
     * dispositivo está demasiado desfasado. Así el replay y el estado fuera de línea
     * usan fechas reales aunque el teléfono tenga la hora equivocada.
     */
    private static Date trustedDeviceTime(MobileEnvelope envelope) {
        try {
            long reported = Instant.parse(envelope.getObservedAt()).toEpochMilli();
            long now = System.currentTimeMillis();
            if (Math.abs(reported - now) > CLOCK_SKEW_THRESHOLD_MS) {
                LOGGER.warn("Hora del dispositivo desfasada {} ms; se usa la hora del servidor",
                        reported - now);
                return new Date();
            }
            return new Date(reported);
        } catch (Exception error) {
            return new Date();
        }
    }
}
