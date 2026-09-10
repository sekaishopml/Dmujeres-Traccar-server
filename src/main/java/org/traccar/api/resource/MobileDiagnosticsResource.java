/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.traccar.api.resource;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.api.BaseResource;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.mobile.MobileDiagnosticsService;
import org.traccar.mobile.MobileDiagnosticsService.Outcome;
import org.traccar.model.Device;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.Objects;

/**
 * Ingesta de diagnósticos del cliente (POST /api/mobile/v1/diagnostics). Mismo modelo de
 * autenticación que {@link MobileConfigResource}: {@code @PermitAll} + la llave compartida
 * {@code mobile.http.apiKey} en la cabecera {@code X-Api-Key}, activo solo con
 * {@code mobile.http.enable=true}, y el dispositivo se resuelve por {@code uniqueId} desde
 * {@code X-Device-Id} (o el query {@code deviceId}), nunca desde el body.
 *
 * <p>Respuestas: 204 en cualquier reporte aceptado o ignorado por rate-limit (el cliente no
 * necesita el cuerpo), 400 si el JSON es inválido, 413 si pasa de 10 000 bytes, 403 si el
 * {@code deviceId} del body no es el dispositivo autenticado, 401/404 según la llave o el
 * canal habilitado. Todo lo demás (whitelist, normalización, eventos, límites) está en
 * {@link MobileDiagnosticsService}, que es singleton porque Jersey crea el recurso por
 * request.</p>
 */
@Path("mobile/v1/diagnostics")
@Produces(MediaType.APPLICATION_JSON)
public class MobileDiagnosticsResource extends BaseResource {

    private final Config config;
    private final MobileDiagnosticsService diagnostics;

    @Inject
    public MobileDiagnosticsResource(Config config, MobileDiagnosticsService diagnostics) {
        this.config = config;
        this.diagnostics = diagnostics;
    }

    @PermitAll
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response submit(
            @HeaderParam("X-Api-Key") String apiKey,
            @HeaderParam("X-Device-Id") String deviceIdHeader,
            @QueryParam("deviceId") String deviceIdQuery,
            String body) throws Exception {
        if (!config.getBoolean(Keys.MOBILE_HTTP_ENABLE)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        String expectedKey = config.getString(Keys.MOBILE_HTTP_API_KEY);
        if (expectedKey == null || !Objects.equals(apiKey, expectedKey)) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        String uniqueId = deviceIdHeader != null && !deviceIdHeader.isBlank()
                ? deviceIdHeader.trim() : deviceIdQuery;
        if (uniqueId == null || uniqueId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        Device device = storage.getObject(Device.class,
                new Request(new Columns.All(), new Condition.Equals("uniqueId", uniqueId)));
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        Outcome outcome = diagnostics.ingest(device, body);
        switch (outcome) {
            case TOO_LARGE:
                return Response.status(Response.Status.REQUEST_ENTITY_TOO_LARGE).build();
            case INVALID:
                return Response.status(Response.Status.BAD_REQUEST).build();
            case DEVICE_MISMATCH:
                return Response.status(Response.Status.FORBIDDEN).build();
            case STORAGE_ERROR:
                return Response.serverError().build();
            case ACCEPTED:
            case THROTTLED:
            default:
                return Response.noContent().build();
        }
    }
}
