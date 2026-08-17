/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.traccar.api.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.traccar.api.BaseResource;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Device;
import org.traccar.model.Permission;
import org.traccar.model.User;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/** Admin-only provisioning endpoint for a Traccar device and its MQTT credentials. */
@Path("mobile/provision")
@Produces(MediaType.APPLICATION_JSON)
public class MobileProvisionResource extends BaseResource {

    private final Config config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Inject
    public MobileProvisionResource(Config config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        httpClient = HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response provision(ProvisionRequest request) throws Exception {
        permissionsService.checkAdmin(getUserId());
        validate(request);

        Device device = storage.getObject(Device.class,
                new Request(new Columns.All(), new Condition.Equals("uniqueId", request.getUsername())));
        boolean created = false;
        if (device == null) {
            device = new Device();
            device.setName(request.getName() == null || request.getName().isBlank()
                    ? request.getUsername() : request.getName());
            device.setUniqueId(request.getUsername());
            device.getAttributes().put("mobile.intervalSeconds", request.getIntervalSeconds());
            device.getAttributes().put("mobile.bufferMax", request.getBufferMax());
            device.getAttributes().put("mobile.bufferPolicy", request.getBufferPolicy());
            device.getAttributes().put("mobile.ackTimeoutSeconds", request.getAckTimeoutSeconds());
            device.getAttributes().put("mobile.maxRetries", request.getMaxRetries());
            device.setId(storage.addObject(device, new Request(new Columns.Exclude("id"))));
            storage.addPermission(new Permission(User.class, getUserId(), Device.class, device.getId()));
            created = true;
        } else {
            device.getAttributes().put("mobile.intervalSeconds", request.getIntervalSeconds());
            device.getAttributes().put("mobile.bufferMax", request.getBufferMax());
            device.getAttributes().put("mobile.bufferPolicy", request.getBufferPolicy());
            device.getAttributes().put("mobile.ackTimeoutSeconds", request.getAckTimeoutSeconds());
            device.getAttributes().put("mobile.maxRetries", request.getMaxRetries());
            storage.updateObject(device, new Request(
                    new Columns.Include("attributes"), new Condition.Equals("id", device.getId())));
        }

        try {
            createMqttUser(request.getUsername(), request.getPassword());
        } catch (Exception error) {
            if (created) {
                storage.removeObject(Device.class, new Request(new Condition.Equals("id", device.getId())));
            }
            throw error;
        }

        return Response.status(created ? Response.Status.CREATED : Response.Status.OK)
                .entity(new ProvisionResponse(device.getId(), request.getUsername(),
                        request.getIntervalSeconds(), request.getBufferMax(),
                        request.getBufferPolicy(), request.getAckTimeoutSeconds(),
                        request.getMaxRetries())).build();
    }

    private void validate(ProvisionRequest request) {
        if (request == null || request.getUsername() == null
                || !request.getUsername().matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("Invalid username");
        }
        if (request.getPassword() == null || request.getPassword().length() < 8) {
            throw new IllegalArgumentException("Password must contain at least 8 characters");
        }
        if (request.getIntervalSeconds() < 3 || request.getIntervalSeconds() > 300) {
            throw new IllegalArgumentException("intervalSeconds must be between 3 and 300");
        }
        if (request.getBufferMax() < 10 || request.getBufferMax() > 5000) {
            throw new IllegalArgumentException("bufferMax must be between 10 and 5000");
        }
        if (!"drop_oldest".equals(request.getBufferPolicy())
                && !"stop_capture".equals(request.getBufferPolicy())) {
            throw new IllegalArgumentException("bufferPolicy must be drop_oldest or stop_capture");
        }
        if (request.getAckTimeoutSeconds() < 5 || request.getAckTimeoutSeconds() > 60) {
            throw new IllegalArgumentException("ackTimeoutSeconds must be between 5 and 60");
        }
        if (request.getMaxRetries() < 3 || request.getMaxRetries() > 200) {
            throw new IllegalArgumentException("maxRetries must be between 3 and 200");
        }
    }

    private void createMqttUser(String username, String password) throws Exception {
        String base = config.getString(Keys.EMQX_API_URL).replaceAll("/$", "") + "/api/v5";
        String authId = URLEncoder.encode(config.getString(Keys.EMQX_AUTH_ID), StandardCharsets.UTF_8);
        String endpoint = base + "/authentication/" + authId + "/users";
        String authorization = apiAuthorization(base);
        String body = objectMapper.writeValueAsString(new MqttUser(username, password, false));
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", authorization)
                .header("Content-Type", MediaType.APPLICATION_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 409) {
            // Idempotente: si el usuario MQTT ya existe, se actualiza su contraseña.
            String encoded = URLEncoder.encode(username, StandardCharsets.UTF_8);
            String updateBody = objectMapper.writeValueAsString(new PasswordUpdate(password));
            HttpRequest update = HttpRequest.newBuilder(URI.create(endpoint + "/" + encoded))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", authorization)
                    .header("Content-Type", MediaType.APPLICATION_JSON)
                    .PUT(HttpRequest.BodyPublishers.ofString(updateBody))
                    .build();
            HttpResponse<String> updateResponse = httpClient.send(update, HttpResponse.BodyHandlers.ofString());
            if (updateResponse.statusCode() < 200 || updateResponse.statusCode() >= 300) {
                throw new StorageException("EMQX user update failed: HTTP " + updateResponse.statusCode()
                        + " body=" + updateResponse.body());
            }
            return;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new StorageException("EMQX user creation failed: HTTP " + response.statusCode());
        }
    }

    private String apiAuthorization(String base) throws Exception {
        String key = config.getString(Keys.EMQX_API_KEY);
        String secret = config.getString(Keys.EMQX_API_SECRET);
        if (key != null && !key.isBlank() && secret != null && !secret.isBlank()) {
            String encoded = Base64.getEncoder().encodeToString((key + ":" + secret)
                    .getBytes(StandardCharsets.UTF_8));
            return "Basic " + encoded;
        }
        String loginBody = objectMapper.writeValueAsString(new DashboardLogin(
                config.getString(Keys.EMQX_DASHBOARD_USER), config.getString(Keys.EMQX_DASHBOARD_PASSWORD)));
        HttpRequest login = HttpRequest.newBuilder(URI.create(base + "/login"))
                .header("Content-Type", MediaType.APPLICATION_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(loginBody)).build();
        HttpResponse<String> response = httpClient.send(login, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new StorageException("EMQX dashboard login failed: HTTP " + response.statusCode());
        }
        return "Bearer " + objectMapper.readTree(response.body()).get("token").asText();
    }

    public static class ProvisionRequest {
        private String username;
        private String password;
        private String name;
        private int intervalSeconds = 10;
        private int bufferMax = 5000;
        private String bufferPolicy = "drop_oldest";
        private int ackTimeoutSeconds = 15;
        private int maxRetries = 30;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getIntervalSeconds() {
            return intervalSeconds;
        }

        public void setIntervalSeconds(int intervalSeconds) {
            this.intervalSeconds = intervalSeconds;
        }

        public int getBufferMax() {
            return bufferMax;
        }

        public void setBufferMax(int bufferMax) {
            this.bufferMax = bufferMax;
        }

        public String getBufferPolicy() {
            return bufferPolicy;
        }

        public void setBufferPolicy(String bufferPolicy) {
            this.bufferPolicy = bufferPolicy;
        }

        public int getAckTimeoutSeconds() {
            return ackTimeoutSeconds;
        }

        public void setAckTimeoutSeconds(int ackTimeoutSeconds) {
            this.ackTimeoutSeconds = ackTimeoutSeconds;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }
    }

    public record ProvisionResponse(long deviceId, String username, int intervalSeconds, int bufferMax,
            String bufferPolicy, int ackTimeoutSeconds, int maxRetries) {}

    private record MqttUser(String user_id, String password, boolean is_superuser) {}

    private record PasswordUpdate(String password) {}

    private record DashboardLogin(String username, String password) {}
}
