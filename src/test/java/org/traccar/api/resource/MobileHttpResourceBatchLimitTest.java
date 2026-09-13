/* Copyright 2026 Anton Tananaev (anton@traccar.org) */
package org.traccar.api.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.api.BaseResource;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.mobile.MobileIngestionService;
import org.traccar.storage.Storage;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Backpressure HTTP del canal móvil: el límite de tamaño del batch (413) se
 * responde ANTES de adquirir el semáforo de proceso, y la rama de error 503
 * lleva Retry-After como la rama de throttle.
 */
public class MobileHttpResourceBatchLimitTest {

    private static final String API_KEY = "test-shared-key";

    private Config config;
    private MobileIngestionService ingestion;
    private MobileHttpResource resource;

    @BeforeEach
    public void setUp() throws Exception {
        config = mock(Config.class);
        when(config.getBoolean(Keys.MOBILE_HTTP_ENABLE)).thenReturn(true);
        when(config.getString(Keys.MOBILE_HTTP_API_KEY)).thenReturn(API_KEY);
        ingestion = mock(MobileIngestionService.class);
        resource = new MobileHttpResource(config, new ObjectMapper(), ingestion);
        Field storageField = BaseResource.class.getDeclaredField("storage");
        storageField.setAccessible(true);
        storageField.set(resource, mock(Storage.class));
    }

    @Test
    public void testBatchTooLargeDecision() {
        assertTrue(MobileHttpResource.batchTooLarge(MobileHttpResource.MAX_BATCH_ITEMS + 1));
        assertFalse(MobileHttpResource.batchTooLarge(MobileHttpResource.MAX_BATCH_ITEMS));
        assertFalse(MobileHttpResource.batchTooLarge(50));
    }

    @Test
    public void testOversizedBatchIs413WithoutIngestion() throws Exception {
        StringBuilder batch = new StringBuilder("[");
        for (int i = 0; i < MobileHttpResource.MAX_BATCH_ITEMS + 1; i++) {
            if (i > 0) {
                batch.append(',');
            }
            batch.append("{\"deviceId\":\"device-123\",\"messageId\":\"m").append(i)
                    .append("\",\"sequence\":1,\"sentAt\":\"2026-09-03T12:00:00Z\",")
                    .append("\"observedAt\":\"2026-09-03T12:00:00Z\",\"type\":\"position\",")
                    .append("\"payload\":{\"latitude\":-33.45,\"longitude\":-70.67}}");
        }
        batch.append(']');

        Response response = resource.submit(API_KEY, batch.toString());

        assertEquals(Response.Status.REQUEST_ENTITY_TOO_LARGE.getStatusCode(), response.getStatus());
        verify(ingestion, never()).process(any(), anyString(), any());
    }

    @Test
    public void testBatchAtLimitIsProcessed() throws Exception {
        StringBuilder batch = new StringBuilder("[");
        for (int i = 0; i < MobileHttpResource.MAX_BATCH_ITEMS; i++) {
            if (i > 0) {
                batch.append(',');
            }
            batch.append("{\"deviceId\":\"device-123\",\"messageId\":\"m").append(i)
                    .append("\",\"sequence\":1,\"sentAt\":\"2026-09-03T12:00:00Z\",")
                    .append("\"observedAt\":\"2026-09-03T12:00:00Z\",\"type\":\"position\",")
                    .append("\"payload\":{\"latitude\":-33.45,\"longitude\":-70.67}}");
        }
        batch.append(']');
        when(ingestion.process(any(), anyString(), any())).thenReturn(
                java.util.concurrent.CompletableFuture.completedFuture(
                        new MobileIngestionService.Result(
                                MobileIngestionService.AckStatus.DUPLICATE, null)));

        Response response = resource.submit(API_KEY, batch.toString());

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        verify(ingestion, org.mockito.Mockito.times(MobileHttpResource.MAX_BATCH_ITEMS))
                .process(any(), anyString(), any());
    }
}
