/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.traccar.api.resource;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.api.BaseResource;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Device;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Configuración remota para la app (la que RemoteConfig.fetch() pide en cada
 * login). Devuelve los mobile.* del device con los mismos defaults de la app,
 * para que el administrador pueda ajustar intervalo, buffer y reintentos sin
 * tocar el teléfono. Usa la misma llave compartida que el fallback HTTP.
 */
@Path("mobile/v1/config")
@Produces(MediaType.APPLICATION_JSON)
public class MobileConfigResource extends BaseResource {

    private final Config config;

    @Inject
    public MobileConfigResource(Config config) {
        this.config = config;
    }

    @PermitAll
    @GET
    public Response getConfig(
            @HeaderParam("X-Api-Key") String apiKey,
            @HeaderParam("X-Device-Id") String deviceIdHeader,
            @QueryParam("deviceId") String deviceIdQuery) throws Exception {
        if (!config.getBoolean(Keys.MOBILE_HTTP_ENABLE)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        String expectedKey = config.getString(Keys.MOBILE_HTTP_API_KEY);
        if (expectedKey == null || !Objects.equals(apiKey, expectedKey)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        String deviceId = deviceIdHeader != null && !deviceIdHeader.isBlank()
                ? deviceIdHeader.trim() : deviceIdQuery;
        if (deviceId == null || deviceId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        Device device = storage.getObject(Device.class,
                new Request(new Columns.All(), new Condition.Equals("uniqueId", deviceId)));
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        Map<String, Object> attrs = device.getAttributes();
        Map<String, Object> out = new HashMap<>();
        out.put("intervalSeconds", num(attrs, "mobile.intervalSeconds", 10L));
        out.put("bufferMax", num(attrs, "mobile.bufferMax", 5000L));
        out.put("bufferPolicy", str(attrs, "mobile.bufferPolicy", "drop_oldest"));
        out.put("ackTimeoutSeconds", num(attrs, "mobile.ackTimeoutSeconds", 15L));
        out.put("maxRetries", num(attrs, "mobile.maxRetries", 30L));
        return Response.ok(out).build();
    }

    private static long num(Map<String, Object> attrs, String key, long def) {
        Object value = attrs.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception error) {
            return def;
        }
    }

    private static String str(Map<String, Object> attrs, String key, String def) {
        Object value = attrs.get(key);
        return value != null ? String.valueOf(value) : def;
    }
}
