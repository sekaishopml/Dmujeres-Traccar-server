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
import org.traccar.database.NotificationManager;
import org.traccar.mobile.MobileApiKeyValidator;
import org.traccar.mobile.MobileJourneyRegistry;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.Collections;
import java.util.Map;

/**
 * Registro de INICIO/FIN de jornada para la app de respaldo (cliente oficial de
 * Traccar con protocolo OsmAnd, que no habla los endpoints móviles propios).
 * Comparte la misma llave X-Api-Key que el canal HTTP y escribe el mismo estado
 * que el canal nativo: atributo {@code mobile.journeyId} en el device, registro
 * en {@link MobileJourneyRegistry} y evento {@code mobileJourneyStarted/Ended}
 * con atributo {@code journeyId} (mismo contrato que
 * {@code MobileTelemetryMonitor.infoEvent} + {@code NotificationManager.updateEvents}).
 * Así el reporte de jornadas y el panel siguen funcionando aunque el teléfono
 * use la app de respaldo.
 */
@Path("mobile/v1/journey")
@Produces(MediaType.APPLICATION_JSON)
public class MobileJourneyResource extends BaseResource {

    private static final Logger LOGGER = LoggerFactory.getLogger(MobileJourneyResource.class);

    public static final String ATTR_JOURNEY_ID = "mobile.journeyId";
    public static final String ATTR_CLIENT = "mobile.client";

    public static final String ACTION_START = "start";
    public static final String ACTION_STOP = "stop";

    private final Config config;
    private final ObjectMapper mapper;
    private final MobileJourneyRegistry journeyRegistry;
    private final NotificationManager notificationManager;

    @Inject
    public MobileJourneyResource(
            Config config,
            ObjectMapper mapper,
            MobileJourneyRegistry journeyRegistry,
            NotificationManager notificationManager) {
        this.config = config;
        this.mapper = mapper;
        this.journeyRegistry = journeyRegistry;
        this.notificationManager = notificationManager;
    }

    @PermitAll
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response submit(@HeaderParam("X-Api-Key") String apiKey, String body) throws Exception {
        if (!config.getBoolean(Keys.MOBILE_HTTP_ENABLE)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        // S1: clave actual + anterior (ventana de rotación de flota), igual que el
        // canal HTTP; en lo demás replica las validaciones de MobileConfigResource.
        if (!MobileApiKeyValidator.isValid(apiKey,
                config.getString(Keys.MOBILE_HTTP_API_KEY),
                config.getString(Keys.MOBILE_HTTP_API_KEY_PREVIOUS))) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception error) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        String deviceId = text(root, "deviceId");
        if (deviceId == null) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        Device device = storage.getObject(Device.class,
                new Request(new Columns.All(), new Condition.Equals("uniqueId", deviceId)));
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        String action = text(root, "action");
        long now = System.currentTimeMillis();
        if (ACTION_START.equals(action)) {
            startJourney(device, root, now);
        } else if (ACTION_STOP.equals(action)) {
            stopJourney(device, root, now);
        } else {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        return Response.ok(Map.of("ok", true)).build();
    }

    /**
     * START: fija {@code mobile.journeyId} (el del body o now) y {@code mobile.client},
     * lo persiste en el device, registra la jornada en memoria y publica el evento.
     * Persistencia y evento van en try/catch separados: un fallo no cambia la
     * respuesta (la app de respaldo reintenta por su cuenta contra su propio buffer).
     */
    private void startJourney(Device device, JsonNode root, long now) {
        long journeyId = optLong(root, "journeyId", now);
        if (journeyId <= 0L) {
            journeyId = now;
        }
        String client = text(root, "client");
        try {
            device.getAttributes().put(ATTR_JOURNEY_ID, journeyId);
            if (client != null) {
                device.getAttributes().put(ATTR_CLIENT, client);
            }
            storage.updateObject(device, new Request(
                    new Columns.Include("attributes"), new Condition.Equals("id", device.getId())));
        } catch (Exception error) {
            LOGGER.warn("Failed to persist journey start for device {}", device.getUniqueId(), error);
        }
        journeyRegistry.start(device.getId(), journeyId);
        publishEvent(device, Event.TYPE_MOBILE_JOURNEY_STARTED, journeyId);
    }

    /**
     * STOP: resuelve el journeyId del body o, si no viene, del último guardado en
     * {@code mobile.journeyId} (fallback: now), pone el atributo a 0, cierra la
     * jornada en memoria y publica el evento.
     */
    private void stopJourney(Device device, JsonNode root, long now) {
        long journeyId = optLong(root, "journeyId", 0L);
        if (journeyId <= 0L) {
            journeyId = longAttr(device.getAttributes(), ATTR_JOURNEY_ID, now);
        }
        if (journeyId <= 0L) {
            journeyId = now;
        }
        try {
            device.getAttributes().put(ATTR_JOURNEY_ID, 0L);
            storage.updateObject(device, new Request(
                    new Columns.Include("attributes"), new Condition.Equals("id", device.getId())));
        } catch (Exception error) {
            LOGGER.warn("Failed to persist journey stop for device {}", device.getUniqueId(), error);
        }
        journeyRegistry.end(device.getId());
        publishEvent(device, Event.TYPE_MOBILE_JOURNEY_ENDED, journeyId);
    }

    /**
     * Publica el evento por NotificationManager (misma vía que el canal nativo:
     * inserta en tc_events y dispara notificaciones) con los atributos de
     * {@code MobileTelemetryMonitor.infoEvent}: mobileSeverity, battery, gps,
     * network y journeyId. Un fallo aquí solo se registra.
     */
    private void publishEvent(Device device, String type, long journeyId) {
        try {
            Map<String, Object> attrs = device.getAttributes();
            Event event = new Event(type, device.getId());
            event.getAttributes().put("mobileSeverity", "info");
            event.getAttributes().put("battery", intAttr(attrs, "mobile.battery", -1));
            String gps = textAttr(attrs, "mobile.gps");
            if (gps != null) {
                event.getAttributes().put("gps", gps);
            }
            String network = textAttr(attrs, "mobile.network");
            if (network != null) {
                event.getAttributes().put("network", network);
            }
            event.getAttributes().put("journeyId", journeyId);
            notificationManager.updateEvents(Collections.singletonMap(event, null));
        } catch (Exception error) {
            LOGGER.warn("Failed to publish {} event for device {}", type, device.getUniqueId(), error);
        }
    }

    private static String text(JsonNode root, String field) {
        if (root == null || !root.hasNonNull(field)) {
            return null;
        }
        String value = root.get(field).asText();
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static long optLong(JsonNode root, String field, long fallback) {
        if (root == null || !root.hasNonNull(field)) {
            return fallback;
        }
        JsonNode node = root.get(field);
        if (node.isNumber()) {
            return node.asLong();
        }
        try {
            return Long.parseLong(node.asText().trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String textAttr(Map<String, Object> attrs, String key) {
        Object value = attrs.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    private static int intAttr(Map<String, Object> attrs, String key, int fallback) {
        Object value = attrs.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static long longAttr(Map<String, Object> attrs, String key, long fallback) {
        Object value = attrs.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(value.toString().trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
