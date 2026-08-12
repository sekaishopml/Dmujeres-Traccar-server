/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.traccar.mobile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.message.auth.Mqtt5SimpleAuth;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.LifecycleObject;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.database.DeviceLookupService;
import org.traccar.handler.PositionPersistenceHandler;
import org.traccar.handler.PositionPipeline;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Device;
import org.traccar.model.MobileMessage;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

@Singleton
public class MobileMqttConsumer implements LifecycleObject {

    private static final Logger LOGGER = LoggerFactory.getLogger(MobileMqttConsumer.class);
    private final Config config;
    private final ObjectMapper mapper;
    private final DeviceLookupService devices;
    private final MobileMessageStore messages;
    private final MobileAtomicPersistence atomic;
    private final PositionPipeline pipeline;
    private final CacheManager cacheManager;
    private Mqtt5AsyncClient client;
    private ExecutorService workers;
    private Semaphore capacity;
    private volatile boolean accepting;
    private final ConcurrentMap<String, CompletableFuture<Void>> deviceTails = new ConcurrentHashMap<>();
    private final AtomicLong queueFullCount = new AtomicLong();

    @Inject
    public MobileMqttConsumer(Config config, ObjectMapper mapper, DeviceLookupService devices,
            MobileMessageStore messages, MobileAtomicPersistence atomic,
            PositionPipeline pipeline, CacheManager cacheManager) {
        this.config = config;
        this.mapper = mapper;
        this.devices = devices;
        this.messages = messages;
        this.atomic = atomic;
        this.pipeline = pipeline;
        this.cacheManager = cacheManager;
    }

    @Override
    public synchronized void start() {
        if (!config.getBoolean(Keys.MOBILE_MQTT_ENABLE)) {
            return;
        }
        String url = config.getString(Keys.MOBILE_MQTT_URL);
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("mobile.mqtt.url is required when mobile.mqtt.enable is true");
        }
        int queueSize = Math.max(1, config.getInteger(Keys.MOBILE_MQTT_WORKER_QUEUE));
        capacity = new Semaphore(queueSize + 2);
        workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize), runnable -> {
                    Thread thread = new Thread(runnable, "MobileMqttWorker");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        URI uri = URI.create(url);
        var builder = MqttClient.builder().useMqttVersion5()
                .identifier(config.getString(Keys.MOBILE_MQTT_CLIENT_ID, "traccar-mobile-" + UUID.randomUUID()))
                .serverHost(uri.getHost()).serverPort(uri.getPort() > 0 ? uri.getPort() : 1883);
        if ("mqtts".equalsIgnoreCase(uri.getScheme())) {
            builder.useSslWithDefaultConfig();
        }
        String username = config.getString(Keys.MOBILE_MQTT_USERNAME);
        String password = config.getString(Keys.MOBILE_MQTT_PASSWORD);
        if (username != null) {
            builder.simpleAuth(Mqtt5SimpleAuth.builder().username(username)
                    .password(password == null ? new byte[0] : password.getBytes(StandardCharsets.UTF_8)).build());
        }
        client = builder.buildAsync();
        accepting = true;
        client.connect().thenCompose(ignored -> client.subscribeWith()
                .topicFilter(config.getString(Keys.MOBILE_MQTT_TOPIC)).qos(MqttQos.AT_LEAST_ONCE)
                .callback(this::onPublish).executor(workers).manualAcknowledgement(true).send())
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        LOGGER.error("Mobile MQTT connection failed", error);
                    } else {
                        LOGGER.info("Mobile MQTT consumer started");
                    }
                });
    }

    private void onPublish(Mqtt5Publish publish) {
        if (!accepting) {
            return;
        }
        if (publish.getPayloadAsBytes().length > config.getInteger(Keys.MOBILE_MQTT_MAX_PAYLOAD)) {
            LOGGER.warn("Mobile MQTT payload exceeds configured limit; leaving publish unacknowledged");
            return;
        }
        if (!capacity.tryAcquire()) {
            long count = queueFullCount.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                LOGGER.warn("Mobile MQTT admission capacity full ({} occurrences); leaving publish unacknowledged",
                        count);
            }
            return;
        }
        try {
            workers.execute(() -> {
                try {
                    process(publish).whenComplete((ignored, error) -> capacity.release());
                } catch (RuntimeException error) {
                    capacity.release();
                    LOGGER.error("Mobile MQTT processing failed; leaving publish unacknowledged", error);
                }
            });
        } catch (RejectedExecutionException error) {
            capacity.release();
            long count = queueFullCount.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                LOGGER.warn("Mobile MQTT worker queue full ({} occurrences); leaving publish unacknowledged", count);
            }
        }
    }

    private CompletionStage<Void> process(Mqtt5Publish publish) {
        String topic = publish.getTopic().toString();
        String topicDeviceId;
        try {
            topicDeviceId = resolveTopicDeviceId(topic, config.getString(Keys.MOBILE_MQTT_TOPIC));
        } catch (Exception error) {
            LOGGER.warn("Invalid mobile MQTT topic; leaving publish unacknowledged", error);
            return CompletableFuture.completedFuture(null);
        }

        return deviceTails.compute(topicDeviceId, (deviceId, previous) -> {
            CompletionStage<Void> predecessor = previous == null
                    ? CompletableFuture.completedFuture(null) : previous;
            CompletableFuture<Void> current = predecessor.handle((ignored, error) -> null)
                    .thenCompose(ignored -> processSerial(publish, topicDeviceId))
                    .toCompletableFuture();
            current.whenComplete((ignored, error) -> deviceTails.remove(deviceId, current));
            return current;
        });
    }

    private CompletionStage<Void> processSerial(Mqtt5Publish publish, String topicDeviceId) {
        byte[] payload = publish.getPayloadAsBytes();
        MobileEnvelope envelope = null;
        try {
            envelope = mapper.readValue(payload, MobileEnvelope.class);
            MobileEnvelopeValidator.validate(envelope, topicDeviceId, Instant.now());
            Device device = devices.lookup(new String[] {topicDeviceId});
            if (device == null) {
                return acknowledgeAfter(publishAck(envelope, "rejected"), publish);
            }
            MobileMessageStore.Result result = messages.reserve(device.getId(), envelope, payload);
            if (result.reservation() == MobileMessageStore.Reservation.DUPLICATE) {
                return acknowledgeAfter(publishAck(envelope, "duplicate"), publish);
            } else if (result.reservation() == MobileMessageStore.Reservation.REJECTED) {
                return acknowledgeAfter(publishAck(envelope, "rejected"), publish);
            }

            MobileMessage message = result.message();
            MobileEnvelope acceptedEnvelope = envelope;
            long leaseMs = config.getInteger(Keys.MOBILE_MQTT_LEASE_SECONDS) * 1000L;
            String leaseToken = atomic.claim(message, leaseMs);
            if (leaseToken == null) {
                LOGGER.warn("Mobile message is still processing: {}", acceptedEnvelope.getMessageId());
                return CompletableFuture.completedFuture(null);
            }

            Position position = toPosition(acceptedEnvelope, device.getId());
            String cacheKey = "mobile:" + acceptedEnvelope.getMessageId();
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
                    .thenCompose(result2 -> {
                        if (result2.persisted()) {
                            return acknowledgeAfter(publishAck(acceptedEnvelope, "accepted"), publish);
                        }
                        if (result2.filtered()) {
                            try {
                                messages.completeWithoutPosition(message);
                                return acknowledgeAfter(publishAck(acceptedEnvelope, "accepted"), publish);
                            } catch (Exception completionError) {
                                LOGGER.error("Failed to finalize filtered mobile message; "
                                        + "leaving publish unacknowledged", completionError);
                                return CompletableFuture.completedFuture(null);
                            }
                        }
                        return CompletableFuture.completedFuture(null);
                    })
                    .exceptionally(error -> {
                        LOGGER.error("Mobile atomic processing failed; leaving publish unacknowledged", error);
                        return null;
                    });
        } catch (Exception error) {
            LOGGER.warn("Invalid mobile MQTT message", error);
            if (envelope != null) {
                return acknowledgeAfter(publishAck(envelope, error.getMessage() != null
                        && error.getMessage().contains("expired") ? "expired" : "invalid"), publish);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    private CompletionStage<Void> acknowledgeAfter(CompletionStage<?> acknowledgement, Mqtt5Publish publish) {
        return acknowledgement.handle((ignored, error) -> {
            if (error == null) {
                publish.acknowledge();
            } else {
                LOGGER.warn("Mobile application ACK failed; leaving publish unacknowledged", error);
            }
            return null;
        });
    }

    public static String resolveTopicDeviceId(String topic, String filter) {
        String[] actual = topic.split("/", -1);
        String[] expected = filter.split("/", -1);
        if (actual.length != expected.length) {
            throw new IllegalArgumentException("topic does not match configured filter");
        }
        String deviceId = null;
        for (int i = 0; i < actual.length; i++) {
            if ("+".equals(expected[i])) {
                if (deviceId != null) {
                    throw new IllegalArgumentException("topic filter has multiple device wildcards");
                }
                deviceId = actual[i];
            } else if (!expected[i].equals(actual[i])) {
                throw new IllegalArgumentException("topic does not match configured filter");
            }
        }
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("topic has no device wildcard");
        }
        return deviceId;
    }

    public static Position toPosition(MobileEnvelope envelope, long deviceId) {
        MobileEnvelope.Payload value = envelope.getPayload();
        Position position = new Position("dmj-mqtt");
        position.setDeviceId(deviceId);
        position.setTime(Date.from(Instant.parse(envelope.getObservedAt())));
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

    private CompletionStage<?> publishAck(MobileEnvelope envelope, String status) {
        if (client == null || envelope == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("MQTT client is not connected"));
        }
        String topic = config.getString(Keys.MOBILE_MQTT_ACK_TOPIC)
                .replace("{deviceId}", envelope.getDeviceId());
        try {
            return client.publishWith().topic(topic).qos(MqttQos.AT_LEAST_ONCE)
                    .payload(mapper.writeValueAsBytes(new Ack(envelope, status))).send();
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public record Ack(int schema, String type, String deviceId, String messageId, long sequence,
            String status, String serverReceivedAt) {
        Ack(MobileEnvelope envelope, String status) {
            this(1, "ack", envelope.getDeviceId(), envelope.getMessageId(), envelope.getSequence(), status,
                    Instant.now().toString());
        }
    }

    @Override
    public synchronized void stop() {
        accepting = false;
        if (client != null) {
            client.disconnect().orTimeout(5, TimeUnit.SECONDS).exceptionally(error -> null);
        }
        if (workers != null) {
            workers.shutdownNow();
        }
    }
}
