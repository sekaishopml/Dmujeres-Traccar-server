/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.traccar.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.database.DeviceLookupService;
import org.traccar.handler.PositionPersistenceHandler;
import org.traccar.handler.PositionPipeline;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Device;
import org.traccar.model.MobileMessage;
import org.traccar.model.Position;
import org.traccar.session.ConnectionManager;
import org.traccar.session.cache.CacheManager;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * Contador de rechazos de validación: los WARN se emiten la 1ª vez y cada 100
     * (ver {@link #shouldLogInvalid}) para no inundar el log en tormentas de reintentos.
     */
    private final AtomicLong invalidMessageCount = new AtomicLong();

    private final Config config;
    private final ObjectMapper mapper;
    private final DeviceLookupService devices;
    private final MobileMessageStore messages;
    private final MobileAtomicPersistence atomic;
    private final PositionPipeline pipeline;
    private final CacheManager cacheManager;
    private final ConnectionManager connectionManager;
    private final MobileJourneyRegistry journeyRegistry;
    private final MobileTelemetryApplier telemetry;
    private final MobilePresenceService presence;
    private final MobileQualityFilter quality;

    @Inject
    public MobileIngestionService(Config config, ObjectMapper mapper, DeviceLookupService devices,
            MobileMessageStore messages, MobileAtomicPersistence atomic,
            PositionPipeline pipeline, CacheManager cacheManager, ConnectionManager connectionManager,
            MobileJourneyRegistry journeyRegistry,
            MobileTelemetryApplier telemetry, MobilePresenceService presence,
            MobileQualityFilter quality) {
        this.config = config;
        this.mapper = mapper;
        this.devices = devices;
        this.messages = messages;
        this.atomic = atomic;
        this.pipeline = pipeline;
        this.cacheManager = cacheManager;
        this.connectionManager = connectionManager;
        this.journeyRegistry = journeyRegistry;
        this.telemetry = telemetry;
        this.presence = presence;
        this.quality = quality;
    }

    public CompletionStage<Result> process(byte[] payload, String topicDeviceId) {
        MobileEnvelope envelope = null;
        try {
            envelope = mapper.readValue(payload, MobileEnvelope.class);
            if (isLwtHeartbeat(envelope)) {
                handleLwtHeartbeat(envelope, topicDeviceId);
                return CompletableFuture.completedFuture(new Result(AckStatus.ACCEPTED, null));
            }
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
                MobilePresenceService.PresenceOutcome outcome =
                        presence.handlePresence(device, captured, root, message);
                AckStatus status = outcome == MobilePresenceService.PresenceOutcome.ACCEPTED
                        ? AckStatus.ACCEPTED : AckStatus.PENDING;
                return CompletableFuture.completedFuture(new Result(status, captured));
            }

            Position position = toPosition(captured, device.getId());
            // Filtro de calidad DEDICADO al canal móvil (después de toPosition, antes del
            // pipeline). El bypass del FilterHandler genérico queda intacto; aquí solo se
            // marca valid=false (conservar) o se rechaza el absurdo (accuracy > reject).
            // La reserva ya existe (dedupe por messageId), así que REJECT finaliza con
            // messages.reject sin fila en tc_positions y el móvil borra con rejected.
            MobileQualityFilter.Verdict verdict = quality.apply(position);
            if (verdict == MobileQualityFilter.Verdict.DUPLICATE) {
                // Re-entrega cacheada del mismo fix de red: no se guarda la fila, pero el
                // mensaje se cierra sin posición y el ACK duplicate drena la cola del móvil.
                // Telemetría y ONLINE se aplican igual para no congelar el panel.
                try {
                    telemetry.applyTelemetry(device, root);
                } catch (Exception telemetryError) {
                    LOGGER.warn("Failed to apply mobile telemetry", telemetryError);
                }
                connectionManager.updateDevice(device.getId(), Device.STATUS_ONLINE, new Date());
                connectionManager.updateDevice(true, device);
                try {
                    messages.completeWithoutPosition(message);
                } catch (Exception completionError) {
                    LOGGER.error("Failed to finalize duplicate mobile message", completionError);
                    return CompletableFuture.completedFuture(new Result(AckStatus.PENDING, captured));
                }
                return CompletableFuture.completedFuture(new Result(AckStatus.DUPLICATE, captured));
            }
            if (verdict == MobileQualityFilter.Verdict.REJECT) {
                try {
                    messages.reject(message);
                } catch (Exception completionError) {
                    LOGGER.error("Failed to reject mobile message", completionError);
                    return CompletableFuture.completedFuture(new Result(AckStatus.PENDING, captured));
                }
                return CompletableFuture.completedFuture(new Result(AckStatus.REJECTED, captured));
            }
            JsonNode positionTelemetry = root.path("payload");
            if (positionTelemetry.hasNonNull("battery")) {
                position.set("batteryLevel", positionTelemetry.get("battery").asInt());
            }
            if (positionTelemetry.hasNonNull("network")) {
                position.set("network", positionTelemetry.get("network").asText());
            }
            // Origen del fix (contrato schema:1 con provider): "gps"|"network"|"fused"|
            // "unknown". "network" = re-entrega de red wifi/celular sin GNSS; el panel
            // excluye esos puntos de la geometría de ruta. Opcional: payloads viejos no lo traen.
            if (positionTelemetry.hasNonNull("provider")) {
                position.set("provider", positionTelemetry.get("provider").asText());
            }
            if (positionTelemetry.hasNonNull("fixAgeSec")) {
                position.set("fixAgeSec", positionTelemetry.get("fixAgeSec").asLong());
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
                        if (result2.persisted()) {
                            try {
                                telemetry.applyTelemetry(device, root);
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
                            // Fase B: ya persistido atómicamente (INSERT tc_positions + UPDATE tc_mobile_messages con positionId)
                            // no llamar a completeWithoutPosition que pondría positionId=0 y rompería el link.
                            return new Result(AckStatus.ACCEPTED, captured);
                        }
                        if (result2.filtered()) {
                            // La telemetría (batería, red, estado) se aplica tanto si la
                            // posición se guarda como si se filtra por duplicada/cercana:
                            // si el dispositivo está quieto manda posiciones casi idénticas
                            // que el servidor filtra, y de lo contrario la batería y el
                            // estado "en línea" se congelarían en el panel.
                            try {
                                telemetry.applyTelemetry(device, root);
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
            long invalidCount = invalidMessageCount.incrementAndGet();
            if (shouldLogInvalid(invalidCount)) {
                LOGGER.warn("Invalid mobile message ({} occurrences)", invalidCount, error);
            }
            if (envelope != null) {
                AckStatus status = error.getMessage() != null && error.getMessage().contains("expired")
                        ? AckStatus.EXPIRED : AckStatus.INVALID;
                return CompletableFuture.completedFuture(new Result(status, envelope));
            }
            return CompletableFuture.completedFuture(new Result(AckStatus.INVALID, null));
        }
    }

    /**
     * Detecta el latido LWT del broker: la app configura el will con messageId "lwt-&lt;now&gt;",
     * sequence 0 y presence network=none, y al morir el TCP en Doze el broker lo publica como
     * un publish normal. OJO: las presence normales llevan sequence&gt;0 y nunca entran aquí.
     */
    public static boolean isLwtHeartbeat(MobileEnvelope envelope) {
        if (envelope == null) {
            return false;
        }
        String messageId = envelope.getMessageId();
        if (messageId != null && messageId.startsWith("lwt-")) {
            return true;
        }
        return envelope.getSequence() == 0 && "presence".equals(envelope.getType());
    }

    /**
     * Throttle de WARNs de validación genuinos (sequence&lt;=0 no-LWT, envelope roto):
     * solo la 1ª vez y cada 100, para no inundar el log en tormentas de reintentos.
     */
    public static boolean shouldLogInvalid(long count) {
        return count == 1 || count % 100 == 0;
    }

    /**
     * Contador de rechazos de validación desde el arranque (para tests y diagnóstico).
     */
    public long getInvalidMessageCount() {
        return invalidMessageCount.get();
    }

    /**
     * LWT del broker: no es un mensaje válido (sequence 0) ni aporta telemetría. Solo marca
     * el device offline —mismo patrón que el fin de jornada en {@link MobilePresenceService}—
     * sin reservar mensaje, sin posición y sin WARN. El evento deviceOffline lo emite
     * ConnectionManager solo si el estado realmente cambia. Se pasa time=null para no mover
     * lastUpdate (no hay datos frescos) y no desarmar el cooldown por device de
     * MobileSilenceMonitor con una "recuperación" fantasma.
     */
    private void handleLwtHeartbeat(MobileEnvelope envelope, String topicDeviceId) {
        LOGGER.debug("Mobile LWT heartbeat from device {} ({}); marking offline",
                topicDeviceId, envelope.getMessageId());
        Device device = devices.lookup(new String[] {topicDeviceId});
        if (device == null) {
            return;
        }
        connectionManager.updateDevice(device.getId(), Device.STATUS_OFFLINE, null);
        connectionManager.updateDevice(true, device);
    }

    public static Position toPosition(MobileEnvelope envelope, long deviceId) {
        MobileEnvelope.Payload value = envelope.getPayload();
        Position position = new Position("dmj-mqtt");
        position.setDeviceId(deviceId);
        Date fixTime = trustedDeviceTime(envelope);
        position.setTime(fixTime);
        // Fase B: clock skew no destructivo — siempre se conserva fixTime real,
        // y se guarda serverReceivedAt como atributo para auditoría/diagnóstico.
        // El serverTime del Position ya se inicializa a now() en el constructor,
        // pero además lo exponemos como atributo serverReceivedAt para que sea
        // queryable y visible en el replay sin alterar fixTime.
        long now = System.currentTimeMillis();
        position.getAttributes().put(Position.KEY_SERVER_RECEIVED_AT, new Date(now));
        try {
            long reported = fixTime.getTime();
            long skew = reported - now;
            if (Math.abs(skew) > CLOCK_SKEW_THRESHOLD_MS) {
                // ya logueado en trustedDeviceTime, dejamos marca explícita del skew
                position.getAttributes().put("deviceTimeSkewMs", skew);
            }
        } catch (Exception ignored) {
        }
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
     * Fase B: clock skew no destructivo — siempre conserva la hora del fix (observedAt)
     * para que el replay offline mantenga el hueco temporal real. Si el reloj del
     * dispositivo está desfasado >1h, solo loguea warn y guarda serverReceivedAt
     * como atributo adicional, pero NO sustituye fixTime.
     */
    private static Date trustedDeviceTime(MobileEnvelope envelope) {
        try {
            long reported = Instant.parse(envelope.getObservedAt()).toEpochMilli();
            long now = System.currentTimeMillis();
            if (Math.abs(reported - now) > CLOCK_SKEW_THRESHOLD_MS) {
                LOGGER.warn(
                        "Hora del dispositivo desfasada {} ms; se conserva hora del fix {}, serverReceivedAt={}",
                        reported - now, new Date(reported), new Date(now));
            }
            return new Date(reported);
        } catch (Exception error) {
            return new Date();
        }
    }
}
