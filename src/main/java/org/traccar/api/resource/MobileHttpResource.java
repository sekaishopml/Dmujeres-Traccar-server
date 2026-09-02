/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.traccar.api.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.api.BaseResource;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.mobile.MobileIngestionService;
import org.traccar.mobile.MobileIngestionService.AckStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * HTTP batch fallback for the mobile channel. Accepts the same envelopes as MQTT and shares
 * the same validation, deduplication and atomic persistence, so switching transport never
 * inserts a position twice. Disabled unless {@code mobile.http.enable=true}; authentication
 * uses a shared API key configured via {@code mobile.http.apiKey}.
 *
 * <p>Fase B — pool HTTP: el batch se procesa SERIAL (join secuencial por elemento) para
 * no saturar HikariCP (maxPoolSize=20 en traccar-dev.xml). La app NO debe paralelizar
 * flushes (150 paralelos bloquearían el pool y provocarían SQLException). Si el servidor
 * ya atiende 20 requests concurrentes en este endpoint, responde 503 Retry-After en vez
 * de encolar y tirar el pool. Esto protege el replay offline de picos de ingesta.</p>
 */
@Path("mobile/v1/positions")
@Produces(MediaType.APPLICATION_JSON)
public class MobileHttpResource extends BaseResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(MobileHttpResource.class);

    // Fase B: semáforo para no bloquear HikariCP con 150 flushes paralelos.
    // 20 coincide con database.maxPoolSize en traccar-dev.xml; si se excede,
    // devolvemos 503 Retry-After y el cliente reintenta con backoff.
    private static final Semaphore CONCURRENCY_LIMIT = new Semaphore(20);

    private final Config config;
    private final ObjectMapper mapper;
    private final MobileIngestionService ingestion;

    @Inject
    public MobileHttpResource(Config config, ObjectMapper mapper, MobileIngestionService ingestion) {
        this.config = config;
        this.mapper = mapper;
        this.ingestion = ingestion;
    }

    @PermitAll
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response submit(@HeaderParam("X-Api-Key") String apiKey, String body) {
        if (!config.getBoolean(Keys.MOBILE_HTTP_ENABLE)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        String expectedKey = config.getString(Keys.MOBILE_HTTP_API_KEY);
        if (expectedKey == null || !Objects.equals(apiKey, expectedKey)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        // Fase B: backpressure con semáforo 20 para no agotar HikariCP.
        // Si hay 20 requests concurrentes ya en curso, devolvemos 503 con Retry-After
        // para que la app haga backoff y reintente el batch, en vez de encolar
        // y provocar SQLException por pool agotado.
        if (!CONCURRENCY_LIMIT.tryAcquire()) {
            LOGGER.warn("Mobile HTTP concurrency limit reached (20); returning 503 Retry-After");
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "2")
                    .entity(new Result(List.of(new Ack(null, null, 0, "throttled"))))
                    .build();
        }
        try {
            List<Ack> acks = new ArrayList<>();
            JsonNode root;
            try {
                root = mapper.readTree(body);
            } catch (Exception error) {
                LOGGER.warn("Invalid mobile HTTP batch payload", error);
                return Response.status(Response.Status.BAD_REQUEST).build();
            }
            if (!root.isArray() || root.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST).build();
            }

            // Se pasan los bytes crudos de cada nodo del JSON (no el envelope re-serializado) para
            // no perder campos de telemetría como battery/pending/network que MobileEnvelope.Payload
            // no modela. El canal MQTT ya hace esto con los bytes originales del broker.
            // Fase B: batch SERIAL — cada elemento hace join secuencial (no se paraleliza).
            // La app debe enviar 1 batch a la vez y no lanzar 150 flushes paralelos.
            for (JsonNode node : root) {
                String deviceId = node.path("deviceId").asText(null);
                if (deviceId == null) {
                    acks.add(new Ack(null, null, 0, "invalid"));
                    continue;
                }
                try {
                    byte[] payload = mapper.writeValueAsBytes(node);
                    AckStatus status = ingestion.process(payload, deviceId)
                            .thenApply(result -> result.status()).toCompletableFuture().join();
                    acks.add(new Ack(deviceId, node.path("messageId").asText(null),
                            node.path("sequence").asLong(0), status.name().toLowerCase()));
                } catch (Exception error) {
                    LOGGER.warn("Mobile HTTP message failed", error);
                    acks.add(new Ack(deviceId, node.path("messageId").asText(null),
                            node.path("sequence").asLong(0), "error"));
                }
            }

            if (acks.stream().anyMatch(ack -> "error".equals(ack.status()))) {
                return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(new Result(acks)).build();
            }
            return Response.ok(new Result(acks)).build();
        } finally {
            CONCURRENCY_LIMIT.release();
        }
    }

    public record Ack(String deviceId, String messageId, long sequence, String status) {}

    public record Result(List<Ack> results) {}
}
