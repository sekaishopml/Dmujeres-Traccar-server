/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
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
package org.traccar.api.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.traccar.mobile.MobileApiKeyValidator;
import org.traccar.mobile.OtaRolloutPolicy;
import org.traccar.model.Device;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.nio.file.Files;
import java.util.Map;

/**
 * OTA gradual (rolling rollout): {@code GET /api/mobile/v1/ota}. Misma
 * autenticación que {@link MobileConfigResource} ({@code @PermitAll} + llave
 * compartida {@code X-Api-Key}, activo solo con {@code mobile.http.enable=true})
 * y el equipo se resuelve por {@code uniqueId} desde {@code X-Device-Id} o el
 * query {@code deviceId}, nunca desde el body.
 *
 * <p>Fuente de datos (elegida frente a env para permitir cambios en caliente
 * sin reiniciar el servidor): el mismo {@code latest.json} que publica
 * {@code infrastructure/scripts/publish-ota.sh} más un opcional
 * {@code rollout.json} en la misma carpeta con {@code {"percent": N,
 * "paused": bool}}; ausente o ilegible = {@code percent 100, paused false}.
 * La carpeta se resuelve en este orden: env {@code DMJ_OTA_DIR} →
 * {@code web.path} si contiene {@code latest.json} →
 * {@code /DMujeres-Tracking/dashboard/public} (default documentado).</p>
 *
 * <p>Respuestas: el MISMO JSON de {@code latest.json} (version, versionCode,
 * url, notes, sha256, minVersionCode) si el rollout le da paso; {@code
 * {"update": false}} si no le toca; 400 sin versionCode/identidad, 401 sin
 * llave válida, 404 si el canal está apagado o no hay latest.json. La decisión
 * vive en {@link OtaRolloutPolicy} (pura y testeable en JVM).</p>
 */
@Path("mobile/v1/ota")
@Produces(MediaType.APPLICATION_JSON)
public class OtaResource extends BaseResource {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(OtaResource.class);

    /** Carpeta por defecto del canal OTA (la que publica publish-ota.sh). */
    static final String DEFAULT_OTA_DIR = "/DMujeres-Tracking/dashboard/public";

    /** Override de la carpeta del canal OTA sin reiniciar el servidor. */
    static final String OTA_DIR_ENV = "DMJ_OTA_DIR";

    private final Config config;
    private final ObjectMapper objectMapper;

    @Inject
    public OtaResource(Config config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @PermitAll
    @GET
    public Response check(
            @HeaderParam("X-Api-Key") String apiKey,
            @HeaderParam("X-Device-Id") String deviceIdHeader,
            @HeaderParam("User-Agent") String userAgent,
            @QueryParam("deviceId") String deviceIdQuery,
            @QueryParam("versionCode") Long versionCode) throws Exception {
        if (!config.getBoolean(Keys.MOBILE_HTTP_ENABLE)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (!MobileApiKeyValidator.isValid(
                apiKey,
                config.getString(Keys.MOBILE_HTTP_API_KEY),
                config.getString(Keys.MOBILE_HTTP_API_KEY_PREVIOUS))) {
            // Diagnóstico sin exponer la llave: quién y con qué agente intentó.
            LOGGER.warn("OTA 401: llave inválida (device={}, ua={})",
                    deviceIdHeader != null ? deviceIdHeader : deviceIdQuery,
                    userAgent != null ? userAgent.substring(0, Math.min(60, userAgent.length())) : "-");
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        String uniqueId = deviceIdHeader != null && !deviceIdHeader.isBlank()
                ? deviceIdHeader.trim() : deviceIdQuery;
        if (uniqueId == null || uniqueId.isBlank()
                || versionCode == null || versionCode < 0) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        // Igual que /config: un equipo no provisionado no recibe manifiesto.
        Device device = storage.getObject(Device.class,
                new Request(new Columns.All(), new Condition.Equals("uniqueId", uniqueId)));
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        java.nio.file.Path directory = otaDirectory();
        java.nio.file.Path latestFile = directory.resolve("latest.json");
        if (!Files.isReadable(latestFile)) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        JsonNode latest = objectMapper.readTree(Files.readString(latestFile));
        long latestCode = latest.path("versionCode").asLong(0);
        long minVersionCode = latest.path("minVersionCode").asLong(0);
        RolloutSettings rollout = readRollout(directory);
        OtaRolloutPolicy.Decision decision = OtaRolloutPolicy.decide(
                uniqueId, versionCode, latestCode, minVersionCode,
                rollout.percent(), rollout.paused(), rollout.allowList());
        if (decision == OtaRolloutPolicy.Decision.DENIED) {
            recordOtaCheck(device, versionCode, false, userAgent);
            return Response.ok(Map.of("update", false)).build();
        }
        recordOtaCheck(device, versionCode, true, userAgent);
        return Response.ok(latest).build();
    }

    /**
     * Auditoría del canal OTA (sin datos personales): deja en el equipo la hora,
     * la versión instalada, si se sirvió manifiesto y el User-Agent del cliente.
     * Best-effort: un fallo aquí nunca afecta la respuesta. Se limita a un
     * registro por minuto para no escribir de más con clientes que consultan
     * cada 2 minutos.
     */
    private void recordOtaCheck(Device device, long versionCode, boolean update, String userAgent) {
        try {
            Object previous = device.getAttributes().get("mobile.lastOtaCheckAt");
            long last = previous instanceof Number number ? number.longValue() : 0L;
            long now = System.currentTimeMillis();
            if (now - last < 60_000L) {
                return;
            }
            device.getAttributes().put("mobile.lastOtaCheckAt", now);
            device.getAttributes().put("mobile.lastOtaVersionCode", versionCode);
            device.getAttributes().put("mobile.lastOtaUpdate", update);
            if (userAgent != null && !userAgent.isBlank()) {
                device.getAttributes().put("mobile.lastOtaUa", userAgent.substring(0, Math.min(80, userAgent.length())));
            }
            storage.updateObject(device, new Request(
                    new Columns.Include("attributes"),
                    new Condition.Equals("id", device.getId())));
        } catch (Exception error) {
            LOGGER.debug("OTA audit write failed: {}", error.getMessage());
        }
    }

    /** Carpeta del canal OTA: env {@code DMJ_OTA_DIR} → web.path útil → default. */
    private java.nio.file.Path otaDirectory() {
        String env = System.getenv(OTA_DIR_ENV);
        if (env != null && !env.isBlank()) {
            return java.nio.file.Path.of(env.trim());
        }
        String webPath = config.getString(Keys.WEB_PATH);
        if (webPath != null && !webPath.isBlank()) {
            java.nio.file.Path candidate = java.nio.file.Path.of(webPath);
            if (Files.isReadable(candidate.resolve("latest.json"))) {
                return candidate;
            }
        }
        return java.nio.file.Path.of(DEFAULT_OTA_DIR);
    }

    /** {@code rollout.json} opcional; ausente/ilegible/fuera de rango = fail-open 100/false. */
    private RolloutSettings readRollout(java.nio.file.Path directory) {
        java.nio.file.Path file = directory.resolve("rollout.json");
        if (!Files.isReadable(file)) {
            return new RolloutSettings(100, false, java.util.List.of());
        }
        try {
            JsonNode json = objectMapper.readTree(Files.readString(file));
            int percent = Math.max(0, Math.min(OtaRolloutPolicy.BUCKETS, json.path("percent").asInt(100)));
            java.util.List<String> allow = new java.util.ArrayList<>();
            if (json.has("allow") && json.path("allow").isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode entry : json.path("allow")) {
                    allow.add(entry.asText());
                }
            }
            return new RolloutSettings(percent, json.path("paused").asBoolean(false), allow);
        } catch (Exception error) {
            return new RolloutSettings(100, false, java.util.List.of());
        }
    }

    private record RolloutSettings(int percent, boolean paused, java.util.List<String> allowList) {
    }
}
