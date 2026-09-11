/* Copyright 2026 Anton Tananaev (anton@traccar.org) */
package org.traccar.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.database.NotificationManager;
import org.traccar.mobile.MobileDiagnosticsService.Outcome;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.session.cache.CacheManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Request;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Ingesta de diagnósticos del cliente: whitelist estricto, coerción defensiva, compactado
 * dentro de presupuesto, rate-limit de 20 s por dispositivo y evento solo con señal.
 * Son pruebas de unidad puras (sin contenedor): el servicio se construye con mocks.
 */
public class MobileDiagnosticsServiceTest {

    private static final long DEVICE_ID = 7L;
    private static final long NOW = 1_760_000_000_000L;

    private final ObjectMapper mapper = new ObjectMapper();

    private Storage storage;
    private NotificationManager notificationManager;
    private MobileDiagnosticsService service;

    @BeforeEach
    public void setUp() {
        storage = mock(Storage.class);
        notificationManager = mock(NotificationManager.class);
        service = new MobileDiagnosticsService(
                storage, mapper, notificationManager, mock(CacheManager.class));
    }

    // ------------------------------------------------------------------------------------
    // Whitelist + normalización
    // ------------------------------------------------------------------------------------

    @Test
    public void testUnknownKeysAreDroppedAndSchemaKept() throws Exception {
        String body = "{\"deviceId\":7,\"ts\":123,\"report\":{"
                + "\"app\":{\"versionCode\":\"59\",\"versionName\":\"  1.0.59  \",\"secret\":\"x\"},"
                + "\"journey\":{\"active\":true,\"elapsedMs\":1500.9,\"startAt\":123,\"extra\":1},"
                + "\"buffer\":{\"pending\":12,\"max\":5000,\"policy\":\"DROP_OLDEST\"},"
                + "\"mqtt\":{\"status\":\"DOWN\",\"lastAckAt\":120,\"lastFixAt\":121,\"reconnects\":2},"
                + "\"net\":{\"cause\":\"WiFi_Lost\",\"cellular\":false,\"airplane\":false},"
                + "\"power\":{\"battery\":78,\"exempt\":false,\"idleMs\":1000},"
                + "\"health\":{\"crashes24h\":0,\"anrs24h\":0,\"stuckStops\":0,\"clockSteps24h\":0,\"speedStuck24h\":3},"
                + "\"spy\":{\"token\":\"nope\"}},\"rootLevel\":\"gone\"}";

        service.ingest(device(), body, NOW);

        JsonNode normalized = json(persistedDiagnostics());
        assertEquals(7L, normalized.get("deviceId").asLong());
        assertEquals(123L, normalized.get("ts").asLong());
        JsonNode report = normalized.get("report");
        assertEquals(7, size(report));
        // claves desconocidas fuera
        assertFalse(report.path("app").has("secret"));
        assertFalse(report.path("journey").has("extra"));
        assertFalse(normalized.has("rootLevel"));
        assertFalse(report.has("spy"));
        // coerciones: string numérica -> int, double -> long truncado, enums en minúsculas
        assertEquals(59, report.path("app").path("versionCode").asInt());
        assertEquals("1.0.59", report.path("app").path("versionName").asText());
        assertEquals(1500L, report.path("journey").path("elapsedMs").asLong());
        assertEquals("drop_oldest", report.path("buffer").path("policy").asText());
        assertEquals("down", report.path("mqtt").path("status").asText());
        assertEquals("wifi_lost", report.path("net").path("cause").asText());
        assertEquals(3, report.path("health").path("speedStuck24h").asInt());
        assertTrue(report.path("journey").path("active").asBoolean());
        assertFalse(report.path("net").path("cellular").asBoolean());
    }

    @Test
    public void testStringSanitizingAndClamping() throws Exception {
        String longText = "a".repeat(500);
        String body = "{\"report\":{\"app\":{\"versionName\":\"" + longText + "\","
                + "\"other\":[1,2,{\"nested\":\"x\"}]},"
                + "\"power\":{\"battery\":9001,\"idleMs\":-5},"
                + "\"health\":{\"crashes24h\":-3,\"lastStartError\":\"boot\\u0000receiver\\n failed\"}}}";

        service.ingest(device(), body, NOW);

        JsonNode normalized = json(persistedDiagnostics());
        String versionName = normalized.path("report").path("app").path("versionName").asText();
        assertEquals(64, versionName.length());
        // arrays/objetos donde va un escalar se descartan
        assertFalse(normalized.path("report").path("app").has("other"));
        // contadores y batería clampados
        assertEquals(100, normalized.path("report").path("power").path("battery").asInt());
        assertEquals(0L, normalized.path("report").path("power").path("idleMs").asLong());
        assertEquals(0, normalized.path("report").path("health").path("crashes24h").asInt());
        assertEquals("boot receiver failed",
                normalized.path("report").path("health").path("lastStartError").asText());
    }

    @Test
    public void testNormalizedNeverExceedsBudget() throws Exception {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"deviceId\":7,\"ts\":").append(NOW).append(",\"report\":{\"health\":{");
        builder.append("\"lastStartError\":\"").append("e".repeat(9000)).append("\"}}}");

        service.ingest(device(), builder.toString(), NOW);

        String persisted = persistedDiagnostics();
        assertNotNull(persisted);
        assertTrue(persisted.length() <= MobileDiagnosticsService.MAX_DIAGNOSTICS_CHARS,
                "persisted diagnostics must stay within "
                        + MobileDiagnosticsService.MAX_DIAGNOSTICS_CHARS + " chars");
        assertTrue(mapper.readTree(persisted).isObject());
    }

    @Test
    public void testShrinkToFitKeepsAttributesWithinVarchar4000() throws Exception {
        Device device = device();
        // tc_devices.attributes es VARCHAR(4000): con un historial de batería casi lleno,
        // el diagnóstico tiene que encogerse en vez de desbordar la columna.
        device.getAttributes().put("mobile.batteryHistory", "[" + "1".repeat(3600) + "]");
        String body = fullReportBody();

        assertEquals(Outcome.ACCEPTED, service.ingest(device, body, NOW));

        String persisted = (String) device.getAttributes().get("lastDiagnostics");
        assertNotNull(persisted);
        assertTrue(mapper.writeValueAsString(device.getAttributes()).length() < 4000,
                "merged attributes must fit the VARCHAR(4000) column");
        assertTrue(persisted.length() <= MobileDiagnosticsService.MAX_DIAGNOSTICS_CHARS);

        // prueba de que el encogimiento ocurrió de verdad: se descartó lo prescindible
        int unconstrained = mapper.writeValueAsString(
                MobileDiagnosticsService.normalize(mapper.readTree(body), NOW)).length();
        assertTrue(unconstrained > persisted.length(),
                "expected shrink: unconstrained " + unconstrained + " vs persisted " + persisted.length());
        JsonNode normalized = mapper.readTree(persisted);
        assertTrue(normalized.isObject());
        assertEquals(123L, normalized.path("ts").asLong());
        assertFalse(normalized.path("report").path("health").has("lastStartError"));
    }

    @Test
    public void testNoRoomLeftInAttributesReturnsStorageError() throws Exception {
        Device device = device();
        device.getAttributes().put("mobile.batteryHistory", "[" + "1".repeat(3950) + "]");
        assertEquals(Outcome.STORAGE_ERROR, service.ingest(device, fullReportBody(), NOW));
        verify(storage, never()).updateObject(any(), any(Request.class));
    }

    private static String fullReportBody() {
        String text = "x".repeat(120);
        return "{\"ts\":123,\"report\":{"
                + "\"app\":{\"versionCode\":59,\"versionName\":\"" + text + "\"},"
                + "\"journey\":{\"active\":true,\"elapsedMs\":3600000,\"startAt\":1725000000000},"
                + "\"buffer\":{\"pending\":4812,\"max\":5000,\"policy\":\"drop_oldest\"},"
                + "\"mqtt\":{\"status\":\"down\",\"lastAckAt\":1725000001000,"
                + "\"lastFixAt\":1725000002000,\"reconnects\":17},"
                + "\"net\":{\"cause\":\"" + text + "\",\"cellular\":true,\"airplane\":false},"
                + "\"power\":{\"battery\":12,\"exempt\":false,\"idleMs\":987654},"
                + "\"health\":{\"crashes24h\":2,\"anrs24h\":1,\"stuckStops\":3,"
                + "\"clockSteps24h\":1,\"lastStartError\":\"" + text + "\"}}}";
    }

    @Test
    public void testAttributesAreMergedNotOverwritten() throws Exception {
        Device device = device();
        device.getAttributes().put("mobile.intervalSeconds", 10L);
        device.getAttributes().put("mobile.journeyId", 42L);

        service.ingest(device, "{\"ts\":123,\"report\":{\"power\":{\"battery\":80}}}", NOW);

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(storage).updateObject(captor.capture(), any(Request.class));
        Map<String, Object> attributes = captor.getValue().getAttributes();
        assertEquals(10L, ((Number) attributes.get("mobile.intervalSeconds")).longValue());
        assertEquals(42L, ((Number) attributes.get("mobile.journeyId")).longValue());
        assertNotNull(attributes.get("lastDiagnostics"));
        assertEquals(NOW, ((Number) attributes.get("lastDiagnosticsAt")).longValue());
    }

    // ------------------------------------------------------------------------------------
    // Body inválido / demasiado grande
    // ------------------------------------------------------------------------------------

    @Test
    public void testMalformedJsonIsInvalid() throws Exception {
        assertEquals(Outcome.INVALID, service.ingest(device(), "{not json", NOW));
        assertEquals(Outcome.INVALID, service.ingest(device(), "", NOW));
        assertEquals(Outcome.INVALID, service.ingest(device(), null, NOW));
        assertEquals(Outcome.INVALID, service.ingest(device(), "[{\"deviceId\":7}]", NOW));
        verify(storage, never()).updateObject(any(), any(Request.class));
    }

    @Test
    public void testEmptyObjectIsStillPersistedWithServerTimestamp() throws Exception {
        assertEquals(Outcome.ACCEPTED, service.ingest(device(), "{}", NOW));
        JsonNode normalized = json(persistedDiagnostics());
        assertEquals(NOW, normalized.get("ts").asLong());
        assertFalse(normalized.has("report"));
    }

    @Test
    public void testBodyOverTenThousandBytesIsRejected() throws Exception {
        String big = "{\"report\":{\"health\":{\"lastStartError\":\"" + "z".repeat(10_001) + "\"}}}";
        assertTrue(big.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 10_000);
        assertEquals(Outcome.TOO_LARGE, service.ingest(device(), big, NOW));
        verify(storage, never()).updateObject(any(), any(Request.class));
    }

    @Test
    public void testBodyJustBelowLimitIsAccepted() {
        String filler = "z".repeat(100);
        String base = "{\"report\":{\"health\":{\"lastStartError\":\"" + filler + "\"}}}";
        // padding dentro de un campo whitelisted hasta rozar los 10 000 bytes
        StringBuilder builder = new StringBuilder(base);
        while (builder.length() < 9_900) {
            builder.insert(0, ' ');
        }
        assertEquals(Outcome.ACCEPTED, service.ingest(device(), builder.toString(), NOW));
    }

    @Test
    public void testDeviceIdMismatchIsRejected() throws Exception {
        assertEquals(Outcome.DEVICE_MISMATCH, service.ingest(device(), "{\"deviceId\":99}", NOW));
        verify(storage, never()).updateObject(any(), any(Request.class));
        // un deviceId legible como string no es numérico: se ignora, no bloquea la ingesta
        assertEquals(Outcome.ACCEPTED, service.ingest(device(), "{\"deviceId\":\"device-123\"}", NOW));
    }

    // ------------------------------------------------------------------------------------
    // Rate-limit
    // ------------------------------------------------------------------------------------

    @Test
    public void testSecondReportWithinTwentySecondsIsIgnored() throws Exception {
        Device device = device();
        assertEquals(Outcome.ACCEPTED, service.ingest(device, "{\"ts\":1,\"report\":{}}", NOW));
        assertEquals(Outcome.THROTTLED, service.ingest(device, "{\"ts\":2,\"report\":{}}", NOW + 19_999));
        assertTrue(service.isThrottled(DEVICE_ID, NOW + 19_999));
        assertFalse(service.isThrottled(DEVICE_ID, NOW + 20_000));

        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(storage, times(1)).updateObject(captor.capture(), any(Request.class));
        assertEquals(1L, ((Number) json((String) captor.getValue().getAttributes().get("lastDiagnostics"))
                .get("ts").asLong()).longValue());
    }

    @Test
    public void testReportAfterTwentySecondsIsAcceptedAgain() throws Exception {
        Device device = device();
        assertEquals(Outcome.ACCEPTED, service.ingest(device, "{\"ts\":1}", NOW));
        assertEquals(Outcome.ACCEPTED, service.ingest(device, "{\"ts\":2}", NOW + MobileDiagnosticsService.THROTTLE_WINDOW_MS));
        verify(storage, times(2)).updateObject(any(), any(Request.class));
    }

    @Test
    public void testThrottleIsPerDevice() {
        Device other = new Device();
        other.setId(8L);
        other.setUniqueId("device-456");
        assertEquals(Outcome.ACCEPTED, service.ingest(device(), "{\"ts\":1}", NOW));
        assertEquals(Outcome.ACCEPTED, service.ingest(other, "{\"ts\":1}", NOW + 1_000));
    }

    @Test
    public void testRejectedReportDoesNotStartTheSilenceWindow() {
        assertEquals(Outcome.INVALID, service.ingest(device(), "nope", NOW));
        assertFalse(service.isThrottled(DEVICE_ID, NOW + 1));
        assertEquals(Outcome.ACCEPTED, service.ingest(device(), "{\"ts\":2}", NOW + 2));
    }

    @Test
    public void testStorageFailureDoesNotStartTheSilenceWindow() throws Exception {
        doThrow(new RuntimeException("db down"))
                .when(storage).updateObject(any(), any(Request.class));
        assertEquals(Outcome.STORAGE_ERROR, service.ingest(device(), "{\"ts\":1}", NOW));
        assertFalse(service.isThrottled(DEVICE_ID, NOW + 1));
    }

    // ------------------------------------------------------------------------------------
    // Evento solo con señal
    // ------------------------------------------------------------------------------------

    @Test
    public void testNoEventForHealthyReport() {
        String healthy = "{\"ts\":1,\"report\":{\"mqtt\":{\"status\":\"connected\",\"reconnects\":0},"
                + "\"health\":{\"crashes24h\":0,\"anrs24h\":4,\"stuckStops\":0,\"clockSteps24h\":0},"
                + "\"power\":{\"battery\":55}}}";
        assertEquals(Outcome.ACCEPTED, service.ingest(device(), healthy, NOW));
        // ANRs solos NO alertan (van en el atributo, no en el evento)
        verify(notificationManager, never()).updateEvents(any());
    }

    @Test
    public void testEventOnEachAlertSignal() throws Exception {
        assertEventForCrashes();
        assertEventForStuckStops();
        assertEventForClockSteps();
        assertEventForMqttDown();
    }

    @Test
    public void testEventHasNullPositionAndDiagnosticsType() {
        service.ingest(device(), "{\"ts\":1,\"report\":{\"health\":{\"stuckStops\":1}}}", NOW);

        ArgumentCaptor<Map<Event, Position>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationManager).updateEvents(captor.capture());
        Map.Entry<Event, Position> entry = captor.getValue().entrySet().iterator().next();
        assertEquals(Event.TYPE_MOBILE_DIAGNOSTICS, entry.getKey().getType());
        assertEquals("mobileDiagnostics", entry.getKey().getType());
        assertEquals(DEVICE_ID, entry.getKey().getDeviceId());
        assertNull(entry.getValue());
        assertEquals(0L, entry.getKey().getPositionId());
        assertEquals(1, ((Number) entry.getKey().getAttributes().get("stuckStops")).intValue());
        assertEquals("warning", entry.getKey().getAttributes().get("mobileSeverity"));
    }

    @Test
    public void testEventOnlyOncePerThrottleWindow() {
        String payload = "{\"ts\":1,\"report\":{\"health\":{\"crashes24h\":3}}}";
        service.ingest(device(), payload, NOW);
        service.ingest(device(), payload, NOW + 5_000);
        verify(notificationManager, times(1)).updateEvents(any());
    }

    private void assertEventForCrashes() {
        resetMocks();
        service.ingest(device(), "{\"ts\":1,\"report\":{\"health\":{\"crashes24h\":1}}}", NOW);
        verify(notificationManager).updateEvents(any());
    }

    private void assertEventForStuckStops() {
        resetMocks();
        service.ingest(device(), "{\"ts\":1,\"report\":{\"health\":{\"stuckStops\":2}}}", NOW);
        verify(notificationManager).updateEvents(any());
    }

    private void assertEventForClockSteps() {
        resetMocks();
        service.ingest(device(), "{\"ts\":1,\"report\":{\"health\":{\"clockSteps24h\":1}}}", NOW);
        verify(notificationManager).updateEvents(any());
    }

    private void assertEventForMqttDown() {
        resetMocks();
        service.ingest(device(), "{\"ts\":1,\"report\":{\"mqtt\":{\"status\":\"Down\"}}}", NOW);
        verify(notificationManager).updateEvents(any());
    }

    private void resetMocks() {
        storage = mock(Storage.class);
        notificationManager = mock(NotificationManager.class);
        service = new MobileDiagnosticsService(
                storage, mapper, notificationManager, mock(CacheManager.class));
    }

    // ------------------------------------------------------------------------------------
    // Criterio puro (sin mocks): útil para regrexar la condición del evento
    // ------------------------------------------------------------------------------------

    @Test
    public void testShouldAlertPureDecision() throws Exception {
        assertFalse(MobileDiagnosticsService.shouldAlert(mapper.readTree("{}")));
        assertFalse(MobileDiagnosticsService.shouldAlert(
                mapper.readTree("{\"report\":{\"health\":{\"crashes24h\":0,\"anrs24h\":9}}}")));
        assertTrue(MobileDiagnosticsService.shouldAlert(
                mapper.readTree("{\"report\":{\"health\":{\"crashes24h\":1}}}")));
        assertTrue(MobileDiagnosticsService.shouldAlert(
                mapper.readTree("{\"report\":{\"health\":{\"stuckStops\":1}}}")));
        assertTrue(MobileDiagnosticsService.shouldAlert(
                mapper.readTree("{\"report\":{\"health\":{\"clockSteps24h\":1}}}")));
        assertTrue(MobileDiagnosticsService.shouldAlert(
                mapper.readTree("{\"report\":{\"mqtt\":{\"status\":\"down\"}}}")));
        assertFalse(MobileDiagnosticsService.shouldAlert(
                mapper.readTree("{\"report\":{\"mqtt\":{\"status\":\"connected\"}}}")));
    }

    private static int size(JsonNode node) {
        int count = 0;
        for (JsonNode ignored : node) {
            count++;
        }
        return count;
    }

    /** Cadena lastDiagnostics que el servicio mandó a la BD en el último updateObject. */
    private String persistedDiagnostics() throws Exception {
        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(storage).updateObject(captor.capture(), any(Request.class));
        return (String) captor.getValue().getAttributes().get("lastDiagnostics");
    }

    private static Device device() {
        Device device = new Device();
        device.setId(DEVICE_ID);
        device.setUniqueId("device-123");
        return device;
    }

    private JsonNode json(String value) throws Exception {
        return mapper.readTree(value);
    }
}
