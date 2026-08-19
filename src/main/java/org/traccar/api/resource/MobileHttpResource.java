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

/**
 * HTTP batch fallback for the mobile channel. Accepts the same envelopes as MQTT and shares
 * the same validation, deduplication and atomic persistence, so switching transport never
 * inserts a position twice. Disabled unless {@code mobile.http.enable=true}; authentication
 * uses a shared API key configured via {@code mobile.http.apiKey}.
 */
@Path("mobile/v1/positions")
@Produces(MediaType.APPLICATION_JSON)
public class MobileHttpResource extends BaseResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(MobileHttpResource.class);

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
    }

    public record Ack(String deviceId, String messageId, long sequence, String status) {}

    public record Result(List<Ack> results) {}
}
