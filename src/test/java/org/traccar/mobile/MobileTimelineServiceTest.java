package org.traccar.mobile;

import org.junit.jupiter.api.Test;
import org.traccar.mobile.MobileTimelineService.Entry;
import org.traccar.mobile.MobileTimelineService.EventRecord;
import org.traccar.mobile.MobileTimelineService.HealthRecord;
import org.traccar.mobile.MobileTimelineService.PositionSample;
import org.traccar.mobile.MobileTimelineService.RecoveryRecord;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P5: caracteriza el timeline operativo desde evidencia. Ningún caso fabrica
 * causa: lo que no está probado por eventos/salud/posiciones se queda fuera o
 * se marca UNKNOWN.
 */
public class MobileTimelineServiceTest {

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 3_600_000L;
    private static final long BASE = 1_800_000_000_000L;

    private static EventRecord event(long timeMs, String type, Map<String, Object> attributes) {
        return new EventRecord(timeMs, type, attributes);
    }

    private static RecoveryRecord recovery(long timeMs, String type, String reason,
            String attemptId, String source) {
        return new RecoveryRecord(timeMs, type, reason, attemptId, source);
    }

    private static HealthRecord health(long timeMs, String eventType, Boolean fgs, Integer outbox) {
        return new HealthRecord(timeMs, eventType, null, null, fgs, null, null, outbox);
    }

    private static PositionSample position(long fixMs, long serverMs) {
        return new PositionSample(fixMs, serverMs);
    }

    private static Entry only(List<Entry> entries, String type) {
        List<Entry> matches = entries.stream().filter(entry -> type.equals(entry.type())).toList();
        assertEquals(1, matches.size(), "expected exactly one " + type + " entry, got " + matches);
        return matches.get(0);
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    // ------------------------------------------------------------------
    // NETWORK_LOSS
    // ------------------------------------------------------------------

    @Test
    public void testNetworkLossPairedWithRestore() {
        List<EventRecord> events = List.of(
                event(BASE + 10 * MINUTE, "mobileNetworkLost", Map.of("reason", "CONNECTION_LOST")),
                event(BASE + 40 * MINUTE, "mobileNetworkRestored", Map.of("reason", "RECONNECT")));
        List<Entry> entries = MobileTimelineService.build(
                events, List.of(), List.of(), List.of(), BASE, BASE + HOUR);

        assertEquals(1, entries.size());
        Entry entry = entries.get(0);
        assertEquals(MobileTimelineService.TYPE_NETWORK_LOSS, entry.type());
        assertEquals(BASE + 10 * MINUTE, entry.timestamp());
        assertEquals(30 * MINUTE, entry.duration());
        assertEquals(MobileTimelineService.SEVERITY_WARNING, entry.severity());
        assertEquals(MobileTimelineService.CONFIDENCE_HIGH, entry.confidence());
        assertEquals(MobileTimelineService.SOURCE_EVENTS, entry.source());
        assertEquals("mobileNetworkRestored", entry.evidence().get("endEventType"));
        assertEquals("RECONNECT", entry.evidence().get("endReason"));
    }

    @Test
    public void testNetworkLossWithoutRestoreHasNoDuration() {
        List<EventRecord> events = List.of(
                event(BASE + 10 * MINUTE, "mobileNetworkLost", Map.of("reason", "CONNECTION_LOST")));
        List<Entry> entries = MobileTimelineService.build(
                events, List.of(), List.of(), List.of(), BASE, BASE + HOUR);

        Entry entry = only(entries, MobileTimelineService.TYPE_NETWORK_LOSS);
        assertNull(entry.duration());
        assertEquals(MobileTimelineService.CONFIDENCE_HIGH, entry.confidence());
    }

    // ------------------------------------------------------------------
    // PRESENCE_OFFLINE (TIMEOUT_OFFLINE → RECONNECT)
    // ------------------------------------------------------------------

    @Test
    public void testPresenceTimeoutToReconnect() {
        List<EventRecord> events = List.of(
                event(BASE + 20 * MINUTE, "mobilePresenceOffline",
                        Map.of("reason", "TIMEOUT_OFFLINE", "silenceMinutes", 10)),
                event(BASE + 32 * MINUTE, "mobilePresenceRecovered", Map.of("reason", "RECONNECT")));
        List<Entry> entries = MobileTimelineService.build(
                events, List.of(), List.of(), List.of(), BASE, BASE + HOUR);

        Entry entry = only(entries, MobileTimelineService.TYPE_PRESENCE_OFFLINE);
        assertEquals(BASE + 20 * MINUTE, entry.timestamp());
        assertEquals(12 * MINUTE, entry.duration());
        assertEquals("TIMEOUT_OFFLINE", entry.evidence().get("reason"));
        assertEquals("RECONNECT", entry.evidence().get("endReason"));
        assertEquals(MobileTimelineService.CONFIDENCE_HIGH, entry.confidence());
    }

    // ------------------------------------------------------------------
    // RECOVERY_*
    // ------------------------------------------------------------------

    @Test
    public void testRecoverySuccessWithServerEvidence() {
        List<RecoveryRecord> rows = List.of(
                recovery(BASE + 5 * MINUTE, "RECOVERY_ATTEMPT", "stalled:7min", "fcm-1", "FCM"),
                recovery(BASE + 5 * MINUTE + 1, "RECOVERY_SENT", "stalled:7min", "fcm-1", "FCM"),
                recovery(BASE + 6 * MINUTE, "RECOVERY_STARTED", null, "fcm-1", "ANDROID"),
                recovery(BASE + 7 * MINUTE, "RECOVERY_GPS_CONFIRMED", null, "fcm-1", "ANDROID"),
                recovery(BASE + 8 * MINUTE, "RECOVERY_SERVER_ACK", "position-after-probe", "fcm-1", "SERVER"));
        List<Entry> entries = MobileTimelineService.build(
                List.of(), rows, List.of(), List.of(), BASE, BASE + HOUR);

        Entry attempt = only(entries, MobileTimelineService.TYPE_RECOVERY_ATTEMPT);
        assertEquals(BASE + 5 * MINUTE, attempt.timestamp());
        assertEquals("fcm-1", attempt.evidence().get("attemptId"));

        Entry success = only(entries, MobileTimelineService.TYPE_RECOVERY_SUCCESS);
        assertEquals(BASE + 8 * MINUTE, success.timestamp());
        assertEquals(MobileTimelineService.CONFIDENCE_HIGH, success.confidence());
        assertEquals("SERVER_ACK", success.evidence().get("evidence"));
        assertTrue(((List<?>) success.evidence().get("stages")).contains("SERVER_ACK"));
    }

    @Test
    public void testRecoveryAttemptWithoutEvidenceDoesNotInventSuccess() {
        List<RecoveryRecord> rows = List.of(
                recovery(BASE + 5 * MINUTE, "RECOVERY_SENT", "stalled:7min", "fcm-1", "FCM"),
                recovery(BASE + 6 * MINUTE, "RECOVERY_STARTED", null, "fcm-1", "ANDROID"));
        List<Entry> entries = MobileTimelineService.build(
                List.of(), rows, List.of(), List.of(), BASE, BASE + HOUR);

        only(entries, MobileTimelineService.TYPE_RECOVERY_ATTEMPT);
        assertFalse(entries.stream().anyMatch(entry ->
                MobileTimelineService.TYPE_RECOVERY_SUCCESS.equals(entry.type())));
        assertFalse(entries.stream().anyMatch(entry ->
                MobileTimelineService.TYPE_RECOVERY_TIMEOUT.equals(entry.type())));
    }

    @Test
    public void testRecoveryGpsConfirmedOnlyIsMediumConfidence() {
        List<RecoveryRecord> rows = List.of(
                recovery(BASE + 5 * MINUTE, "RECOVERY_SENT", null, "fcm-1", "FCM"),
                recovery(BASE + 7 * MINUTE, "RECOVERY_GPS_CONFIRMED", null, "fcm-1", "ANDROID"));
        List<Entry> entries = MobileTimelineService.build(
                List.of(), rows, List.of(), List.of(), BASE, BASE + HOUR);

        Entry success = only(entries, MobileTimelineService.TYPE_RECOVERY_SUCCESS);
        assertEquals(MobileTimelineService.CONFIDENCE_MEDIUM, success.confidence());
        assertEquals("GPS_CONFIRMED", success.evidence().get("evidence"));
    }

    @Test
    public void testRecoveryExplicitSuccessEvent() {
        List<RecoveryRecord> rows = List.of(
                recovery(BASE + MINUTE, "RECOVERY_SENT", null, "fcm-2", "FCM"),
                recovery(BASE + 9 * MINUTE, "RECOVERY_SUCCESS", "end-to-end", "fcm-2", "SERVER"));
        List<Entry> entries = MobileTimelineService.build(
                List.of(), rows, List.of(), List.of(), BASE, BASE + HOUR);

        Entry success = only(entries, MobileTimelineService.TYPE_RECOVERY_SUCCESS);
        assertEquals(BASE + 9 * MINUTE, success.timestamp());
        assertEquals("RECOVERY_SUCCESS", success.evidence().get("eventType"));
        assertEquals(MobileTimelineService.CONFIDENCE_HIGH, success.confidence());
    }

    @Test
    public void testRecoveryTimeoutWithReason() {
        List<RecoveryRecord> rows = List.of(
                recovery(BASE + MINUTE, "RECOVERY_SENT", null, "fcm-3", "FCM"),
                recovery(BASE + 8 * MINUTE, "RECOVERY_TIMEOUT", "TIMEOUT_NO_GPS", "fcm-3", "SERVER"));
        List<Entry> entries = MobileTimelineService.build(
                List.of(), rows, List.of(), List.of(), BASE, BASE + HOUR);

        Entry timeout = only(entries, MobileTimelineService.TYPE_RECOVERY_TIMEOUT);
        assertEquals(BASE + 8 * MINUTE, timeout.timestamp());
        assertEquals(MobileTimelineService.SEVERITY_WARNING, timeout.severity());
        assertEquals("TIMEOUT_NO_GPS", timeout.evidence().get("reason"));
        assertEquals(MobileTimelineService.CONFIDENCE_HIGH, timeout.confidence());
        assertFalse(entries.stream().anyMatch(entry ->
                MobileTimelineService.TYPE_RECOVERY_SUCCESS.equals(entry.type())),
                "timeout must never be reported as success");
    }

    // ------------------------------------------------------------------
    // CAPTURE_GAP / DELIVERY_DELAY
    // ------------------------------------------------------------------

    @Test
    public void testCaptureGapBetweenFixes() {
        List<PositionSample> positions = List.of(
                position(BASE, BASE),
                position(BASE + MINUTE, BASE + MINUTE),
                position(BASE + 16 * MINUTE, BASE + 16 * MINUTE),
                position(BASE + 17 * MINUTE, BASE + 17 * MINUTE));
        List<Entry> entries = MobileTimelineService.build(
                List.of(), List.of(), List.of(), positions, BASE, BASE + 17 * MINUTE);

        assertEquals(1, entries.size());
        Entry gap = entries.get(0);
        assertEquals(MobileTimelineService.TYPE_CAPTURE_GAP, gap.type());
        assertEquals(BASE + MINUTE, gap.timestamp());
        assertEquals(15 * MINUTE, gap.duration());
        assertEquals(MobileTimelineService.CONFIDENCE_HIGH, gap.confidence());
        assertEquals(MobileTimelineService.SOURCE_POSITIONS, gap.source());
        assertEquals(BASE + MINUTE, number(gap.evidence().get("gapStart")));
        assertEquals(BASE + 16 * MINUTE, number(gap.evidence().get("gapEnd")));
    }

    @Test
    public void testCaptureGapWithoutAnyFixIsNotDeclared() {
        List<Entry> entries = MobileTimelineService.build(
                List.of(), List.of(), List.of(), List.of(), BASE, BASE + HOUR);

        assertTrue(entries.stream().noneMatch(entry ->
                MobileTimelineService.TYPE_CAPTURE_GAP.equals(entry.type())));
        assertEquals(MobileTimelineService.TYPE_UNKNOWN, entries.get(0).type());
    }

    @Test
    public void testDeliveryDelayEpisode() {
        List<PositionSample> positions = List.of(
                position(BASE, BASE),
                position(BASE + 5 * MINUTE, BASE + 20 * MINUTE),
                position(BASE + 10 * MINUTE, BASE + 11 * MINUTE));
        List<Entry> entries = MobileTimelineService.build(
                List.of(), List.of(), List.of(), positions, BASE, BASE + 10 * MINUTE);

        assertEquals(1, entries.size());
        Entry delay = entries.get(0);
        assertEquals(MobileTimelineService.TYPE_DELIVERY_DELAY, delay.type());
        assertEquals(BASE + 5 * MINUTE, delay.timestamp());
        assertEquals(5 * MINUTE, delay.duration());
        assertEquals(MobileTimelineService.SEVERITY_WARNING, delay.severity());
        assertEquals(MobileTimelineService.CONFIDENCE_HIGH, delay.confidence());
        assertEquals(15 * MINUTE, number(delay.evidence().get("maxDelayMs")));
        assertEquals(1, number(delay.evidence().get("samples")));
    }

    @Test
    public void testOngoingDeliveryDelayHasNoDuration() {
        List<PositionSample> positions = List.of(
                position(BASE, BASE),
                position(BASE + 5 * MINUTE, BASE + 20 * MINUTE));
        List<Entry> entries = MobileTimelineService.build(
                List.of(), List.of(), List.of(), positions, BASE, BASE + 5 * MINUTE);

        Entry delay = only(entries, MobileTimelineService.TYPE_DELIVERY_DELAY);
        assertNull(delay.duration());
        assertEquals(Boolean.TRUE, delay.evidence().get("ongoing"));
    }

    // ------------------------------------------------------------------
    // HEALTH: FGS_STATE / OUTBOX_BACKLOG
    // ------------------------------------------------------------------

    @Test
    public void testFgsTransitionsAndOutboxBacklog() {
        List<HealthRecord> rows = List.of(
                health(BASE, "HEARTBEAT", true, 0),
                health(BASE + MINUTE, "STATE_CHANGE", false, 5),
                health(BASE + 2 * MINUTE, "HEARTBEAT", false, 12),
                health(BASE + 3 * MINUTE, "STATE_CHANGE", false, 0),
                health(BASE + 4 * MINUTE, "STATE_CHANGE", true, 0));
        List<Entry> entries = MobileTimelineService.build(
                List.of(), List.of(), rows, List.of(), BASE, BASE + 4 * MINUTE);

        List<Entry> fgs = entries.stream().filter(entry ->
                MobileTimelineService.TYPE_FGS_STATE.equals(entry.type())).toList();
        assertEquals(3, fgs.size());
        assertEquals(Boolean.TRUE, fgs.get(0).evidence().get("fgs"));
        assertEquals(Boolean.FALSE, fgs.get(1).evidence().get("fgs"));
        assertEquals(MobileTimelineService.SEVERITY_WARNING, fgs.get(1).severity());

        Entry backlog = only(entries, MobileTimelineService.TYPE_OUTBOX_BACKLOG);
        assertEquals(BASE + MINUTE, backlog.timestamp());
        assertEquals(2 * MINUTE, backlog.duration());
        assertEquals(12, number(backlog.evidence().get("maxOutbox")));
        assertEquals(BASE + 3 * MINUTE, number(backlog.evidence().get("clearedAt")));
    }

    // ------------------------------------------------------------------
    // JOURNEY / orden / UNKNOWN
    // ------------------------------------------------------------------

    @Test
    public void testChronologicalOrderAndDurations() {
        List<EventRecord> events = List.of(
                event(BASE + 2 * HOUR, "mobileJourneyEnded", Map.of()),
                event(BASE + 30 * MINUTE, "mobileNetworkLost", Map.of()),
                event(BASE + 10 * MINUTE, "mobileJourneyStarted", Map.of()),
                event(BASE + 45 * MINUTE, "mobileNetworkRestored", Map.of()));
        List<Entry> entries = MobileTimelineService.build(
                events, List.of(), List.of(), List.of(), BASE, BASE + 3 * HOUR);

        assertEquals(3, entries.size());
        assertEquals(MobileTimelineService.TYPE_JOURNEY_START, entries.get(0).type());
        assertEquals(MobileTimelineService.TYPE_NETWORK_LOSS, entries.get(1).type());
        assertEquals(MobileTimelineService.TYPE_JOURNEY_END, entries.get(2).type());
        assertTrue(entries.get(0).timestamp() < entries.get(1).timestamp());
        assertTrue(entries.get(1).timestamp() < entries.get(2).timestamp());
        assertEquals(110 * MINUTE, entries.get(0).duration());
        assertEquals(15 * MINUTE, entries.get(1).duration());
        assertNull(entries.get(2).duration());
    }

    @Test
    public void testUnknownWithoutEvidence() {
        List<Entry> entries = MobileTimelineService.build(
                List.of(), List.of(), List.of(), List.of(), BASE, BASE + 2 * HOUR);

        assertEquals(1, entries.size());
        Entry unknown = entries.get(0);
        assertEquals(MobileTimelineService.TYPE_UNKNOWN, unknown.type());
        assertEquals(MobileTimelineService.SEVERITY_UNKNOWN, unknown.severity());
        assertEquals(MobileTimelineService.CONFIDENCE_UNKNOWN, unknown.confidence());
        assertEquals(MobileTimelineService.SOURCE_NONE, unknown.source());
        assertEquals(BASE, unknown.timestamp());
        assertEquals(2 * HOUR, unknown.duration());
    }

    @Test
    public void testEmptyListsNeverThrow() {
        List<Entry> entries = MobileTimelineService.build(null, null, null, null, BASE, BASE);
        assertEquals(1, entries.size());
        assertEquals(MobileTimelineService.TYPE_UNKNOWN, entries.get(0).type());
    }

    // ------------------------------------------------------------------
    // Utilidades HTTP
    // ------------------------------------------------------------------

    @Test
    public void testParseTimeAcceptsEpochAndIso() {
        assertEquals(BASE, MobileTimelineService.parseTime("1800000000000", -1));
        assertEquals(BASE, MobileTimelineService.parseTime("1800000000", -1));
        assertEquals(BASE, MobileTimelineService.parseTime("2027-01-15T08:00:00Z", -1));
        assertEquals(42L, MobileTimelineService.parseTime(null, 42L));
        assertEquals(42L, MobileTimelineService.parseTime("no-es-fecha", 42L));
    }

    @Test
    public void testClampLimit() {
        assertEquals(MobileTimelineService.DEFAULT_LIMIT, MobileTimelineService.clampLimit(null));
        assertEquals(1, MobileTimelineService.clampLimit(0));
        assertEquals(50, MobileTimelineService.clampLimit(50));
        assertEquals(MobileTimelineService.MAX_LIMIT, MobileTimelineService.clampLimit(999_999));
    }
}
