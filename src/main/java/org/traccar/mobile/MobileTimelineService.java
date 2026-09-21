package org.traccar.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * P5 (§41): timeline operativo server-side. Combina la evidencia real disponible
 * (tc_events, tc_recovery_event, tc_device_health, tc_positions) en una línea
 * temporal con {@code timestamp, type, severity, duration, evidence, source,
 * confidence}.
 *
 * <p>Reglas duras: sin evidencia no hay entrada tipada (se devuelve UNKNOWN con
 * confidence UNKNOWN) y NUNCA se inventa causa (p.ej. un silencio no es "OEM
 * KILL" si no hay evento que lo pruebe). {@code duration} solo se rellena
 * cuando existe el par inicio/fin observado.</p>
 *
 * <p>El núcleo {@link #build} es puro: recibe listas en memoria y no habla con
 * la BD, por lo que es testeable de forma determinista. {@link #collect} es la
 * única parte que lee PostgreSQL (solo SELECT, jamás escribe).</p>
 */
@Singleton
public class MobileTimelineService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MobileTimelineService.class);

    public static final long DEFAULT_WINDOW_MS = 24L * 60 * 60 * 1000;
    public static final int DEFAULT_LIMIT = 2000;
    public static final int MAX_LIMIT = 2000;
    /** Hueco de fixes (CAPTURE_GAP): más de 5 min sin posición, igual que continuidad. */
    public static final long CAPTURE_GAP_THRESHOLD_MS = 5L * 60 * 1000;
    /** Retraso de entrega (servertime - fixtime) a partir del cual es evidencia. */
    public static final long DELIVERY_DELAY_THRESHOLD_MS = 5L * 60 * 1000;
    public static final long DELIVERY_DELAY_CRITICAL_MS = 60L * 60 * 1000;
    private static final int MAX_DB_ROWS = 10_000;
    private static final int MAX_ATTRIBUTE_KEYS = 32;

    public static final String TYPE_NETWORK_LOSS = "NETWORK_LOSS";
    public static final String TYPE_PRESENCE_OFFLINE = "PRESENCE_OFFLINE";
    public static final String TYPE_RECOVERY_ATTEMPT = "RECOVERY_ATTEMPT";
    public static final String TYPE_RECOVERY_SUCCESS = "RECOVERY_SUCCESS";
    public static final String TYPE_RECOVERY_TIMEOUT = "RECOVERY_TIMEOUT";
    public static final String TYPE_FGS_STATE = "FGS_STATE";
    public static final String TYPE_OUTBOX_BACKLOG = "OUTBOX_BACKLOG";
    public static final String TYPE_JOURNEY_START = "JOURNEY_START";
    public static final String TYPE_JOURNEY_END = "JOURNEY_END";
    public static final String TYPE_CAPTURE_GAP = "CAPTURE_GAP";
    public static final String TYPE_DELIVERY_DELAY = "DELIVERY_DELAY";
    public static final String TYPE_UNKNOWN = "UNKNOWN";

    public static final String SEVERITY_INFO = "info";
    public static final String SEVERITY_WARNING = "warning";
    public static final String SEVERITY_CRITICAL = "critical";
    public static final String SEVERITY_UNKNOWN = "unknown";

    public static final String CONFIDENCE_HIGH = "HIGH";
    public static final String CONFIDENCE_MEDIUM = "MEDIUM";
    public static final String CONFIDENCE_LOW = "LOW";
    public static final String CONFIDENCE_UNKNOWN = "UNKNOWN";

    public static final String SOURCE_EVENTS = "events";
    public static final String SOURCE_RECOVERY = "recovery";
    public static final String SOURCE_HEALTH = "health";
    public static final String SOURCE_POSITIONS = "positions";
    public static final String SOURCE_NONE = "none";

    private static final String EVENT_NETWORK_LOST = "mobileNetworkLost";
    private static final String EVENT_NETWORK_RESTORED = "mobileNetworkRestored";
    private static final String EVENT_PRESENCE_OFFLINE = "mobilePresenceOffline";
    private static final String EVENT_PRESENCE_RECOVERED = "mobilePresenceRecovered";
    private static final String EVENT_STALLED = "mobileStalled";
    private static final String EVENT_JOURNEY_STARTED = "mobileJourneyStarted";
    private static final String EVENT_JOURNEY_ENDED = "mobileJourneyEnded";

    private static final String EVENTS_SQL = """
            SELECT eventtime, type, attributes
            FROM tc_events
            WHERE deviceid = ? AND eventtime >= ? AND eventtime <= ?
            ORDER BY eventtime ASC
            LIMIT ?
            """;

    private static final String RECOVERY_SQL = """
            SELECT ts, eventtype, reason, attemptid, source
            FROM tc_recovery_event
            WHERE deviceid = ? AND ts >= ? AND ts <= ?
            ORDER BY ts ASC
            LIMIT ?
            """;

    private static final String HEALTH_SQL = """
            SELECT ts, eventtype, reason, healthstate, fgs, motion, network, outbox
            FROM tc_device_health
            WHERE deviceid = ? AND ts >= ? AND ts <= ?
            ORDER BY ts ASC
            LIMIT ?
            """;

    private static final String HEALTH_BASELINE_SQL = """
            SELECT ts, eventtype, reason, healthstate, fgs, motion, network, outbox
            FROM tc_device_health
            WHERE deviceid = ? AND ts < ?
            ORDER BY ts DESC
            LIMIT 1
            """;

    private static final String POSITIONS_SQL = """
            SELECT fixtime, servertime
            FROM tc_positions
            WHERE deviceid = ? AND fixtime >= ? AND fixtime <= ?
            ORDER BY fixtime ASC
            LIMIT ?
            """;

    private static final String POSITION_BASELINE_SQL = """
            SELECT fixtime, servertime
            FROM tc_positions
            WHERE deviceid = ? AND fixtime < ?
            ORDER BY fixtime DESC
            LIMIT 1
            """;

    private final DataSource dataSource;
    private final ObjectMapper mapper;

    @Inject
    public MobileTimelineService(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------
    // Contrato
    // ------------------------------------------------------------------

    /** Evento crudo de tc_events (attributes ya parseados a escalares). */
    public record EventRecord(long timeMs, String type, Map<String, Object> attributes) {}

    /** Evento crudo de tc_recovery_event. */
    public record RecoveryRecord(long timeMs, String eventType, String reason,
            String attemptId, String source) {}

    /** Fila cruda de tc_device_health (solo las columnas que evidencian estado). */
    public record HealthRecord(long timeMs, String eventType, String reason, String healthState,
            Boolean fgs, String motion, String network, Integer outbox) {}

    /** Muestra de tc_positions: fixtime (captura) vs servertime (entrega). */
    public record PositionSample(long fixTimeMs, long serverTimeMs) {}

    /** Entrada del timeline. {@code duration} es null si no hay par inicio/fin. */
    public record Entry(long timestamp, String type, String severity, Long duration,
            Map<String, Object> evidence, String source, String confidence) {}

    /** Respuesta completa del endpoint. */
    public record Timeline(long deviceId, long from, long to, int limit, int count,
            boolean truncated, List<Entry> events) {}

    // ------------------------------------------------------------------
    // Núcleo puro (sin BD)
    // ------------------------------------------------------------------

    /**
     * Construye el timeline a partir de evidencia en memoria. {@code positionTimes}
     * lleva fixtime + servertime (CAPTURE_GAP y DELIVERY_DELAY). Se puede llamar
     * con listas vacías: devuelve un único UNKNOWN, nunca inventa.
     */
    public static List<Entry> build(
            List<EventRecord> events,
            List<RecoveryRecord> recoveryEvents,
            List<HealthRecord> healthRows,
            List<PositionSample> positionTimes,
            long fromMs,
            long toMs) {

        long from = Math.min(fromMs, toMs);
        long to = Math.max(fromMs, toMs);
        List<Entry> entries = new ArrayList<>();

        List<PositionSample> samples = sortedSamples(positionTimes);
        List<long[]> fixGaps = addCaptureGaps(entries, samples, from, to);
        addEventEntries(entries, events, fixGaps, from, to);
        addRecoveryEntries(entries, recoveryEvents, from, to);
        addHealthEntries(entries, healthRows, from, to);
        addDeliveryDelays(entries, samples, from, to);

        entries.removeIf(entry -> !inWindow(entry.timestamp(), from, to));
        if (entries.isEmpty()) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("reason", "no-evidence");
            evidence.put("from", from);
            evidence.put("to", to);
            entries.add(new Entry(from, TYPE_UNKNOWN, SEVERITY_UNKNOWN, to - from,
                    evidence, SOURCE_NONE, CONFIDENCE_UNKNOWN));
        }
        entries.sort(Comparator.comparingLong(Entry::timestamp).thenComparing(Entry::type));
        return entries;
    }

    // ------------------------------------------------------------------
    // tc_events: pares NetworkLost/Restored, PresenceOffline/Recovered, jornada
    // ------------------------------------------------------------------

    private static void addEventEntries(List<Entry> entries, List<EventRecord> events,
            List<long[]> fixGaps, long from, long to) {
        List<EventRecord> ordered = new ArrayList<>();
        if (events != null) {
            for (EventRecord event : events) {
                if (event != null && event.timeMs() > 0) {
                    ordered.add(event);
                }
            }
        }
        ordered.sort(Comparator.comparingLong(EventRecord::timeMs));
        boolean[] consumed = new boolean[ordered.size()];
        for (int index = 0; index < ordered.size(); index++) {
            EventRecord event = ordered.get(index);
            String type = event.type();
            if (type == null || !inWindow(event.timeMs(), from, to)) {
                continue;
            }
            switch (type) {
                case EVENT_NETWORK_LOST -> {
                    EventRecord end = findNext(ordered, index, EVENT_NETWORK_RESTORED, consumed);
                    entries.add(new Entry(event.timeMs(), TYPE_NETWORK_LOSS, SEVERITY_WARNING,
                            durationOrNull(event, end), eventEvidence(event, end),
                            SOURCE_EVENTS, CONFIDENCE_HIGH));
                }
                case EVENT_PRESENCE_OFFLINE -> {
                    EventRecord end = findNext(ordered, index, EVENT_PRESENCE_RECOVERED, consumed);
                    entries.add(new Entry(event.timeMs(), TYPE_PRESENCE_OFFLINE, SEVERITY_WARNING,
                            durationOrNull(event, end), eventEvidence(event, end),
                            SOURCE_EVENTS, CONFIDENCE_HIGH));
                }
                case EVENT_JOURNEY_STARTED -> {
                    EventRecord end = findNext(ordered, index, EVENT_JOURNEY_ENDED, null);
                    entries.add(new Entry(event.timeMs(), TYPE_JOURNEY_START, SEVERITY_INFO,
                            durationOrNull(event, end), eventEvidence(event, end),
                            SOURCE_EVENTS, CONFIDENCE_HIGH));
                }
                case EVENT_JOURNEY_ENDED -> entries.add(new Entry(event.timeMs(), TYPE_JOURNEY_END,
                        SEVERITY_INFO, null, eventEvidence(event, null),
                        SOURCE_EVENTS, CONFIDENCE_HIGH));
                case EVENT_STALLED -> {
                    // El evento dice "recibe datos pero no hay coordenadas nuevas": es
                    // evidencia de hueco de captura, no de una causa concreta. Se
                    // descarta si un hueco de fixes ya cubre ese instante.
                    if (!insideAnyGap(fixGaps, event.timeMs())) {
                        entries.add(new Entry(event.timeMs(), TYPE_CAPTURE_GAP, SEVERITY_WARNING,
                                null, eventEvidence(event, null), SOURCE_EVENTS, CONFIDENCE_MEDIUM));
                    }
                }
                default -> {
                    // deviceOnline/Offline, deviceMoving/Stopped, suspect, diagnostics:
                    // sin tipo de timeline asignable sin inventar causa.
                }
            }
        }
    }

    private static EventRecord findNext(List<EventRecord> ordered, int startIndex, String type,
            boolean[] consumed) {
        for (int index = startIndex + 1; index < ordered.size(); index++) {
            if (consumed != null && consumed[index]) {
                continue;
            }
            EventRecord candidate = ordered.get(index);
            if (type.equals(candidate.type())) {
                if (consumed != null) {
                    consumed[index] = true;
                }
                return candidate;
            }
        }
        return null;
    }

    private static Long durationOrNull(EventRecord start, EventRecord end) {
        return end == null ? null : Math.max(0, end.timeMs() - start.timeMs());
    }

    private static Map<String, Object> eventEvidence(EventRecord event, EventRecord end) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        if (event.attributes() != null) {
            evidence.putAll(event.attributes());
        }
        evidence.put("eventType", event.type());
        evidence.put("time", event.timeMs());
        if (end != null) {
            evidence.put("endEventType", end.type());
            evidence.put("endTime", end.timeMs());
            if (end.attributes() != null && end.attributes().get("reason") != null) {
                evidence.put("endReason", end.attributes().get("reason"));
            }
        }
        return evidence;
    }

    // ------------------------------------------------------------------
    // tc_recovery_event: intento, éxito (solo con evidencia) y timeout
    // ------------------------------------------------------------------

    private static void addRecoveryEntries(List<Entry> entries, List<RecoveryRecord> recoveryEvents,
            long from, long to) {
        Map<String, List<RecoveryRecord>> byAttempt = new LinkedHashMap<>();
        if (recoveryEvents != null) {
            for (RecoveryRecord row : recoveryEvents) {
                if (row == null || row.timeMs() <= 0) {
                    continue;
                }
                String key = row.attemptId() == null || row.attemptId().isBlank()
                        ? "single:" + row.timeMs() + ":" + row.eventType()
                        : row.attemptId();
                byAttempt.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
            }
        }
        for (List<RecoveryRecord> group : byAttempt.values()) {
            group.sort(Comparator.comparingLong(RecoveryRecord::timeMs));
            RecoveryRecord attempt = null;
            RecoveryRecord success = null;
            RecoveryRecord timeout = null;
            RecoveryRecord lastStage = null;
            List<String> stages = new ArrayList<>();
            for (RecoveryRecord row : group) {
                String normalized = normalizeRecoveryType(row.eventType());
                switch (normalized) {
                    case "ATTEMPT", "SENT" -> {
                        if (attempt == null) {
                            attempt = row;
                        }
                    }
                    case "SUCCESS" -> success = row;
                    case "TIMEOUT" -> timeout = row;
                    case "RECEIVED", "STARTED", "FGS_ACTIVE", "TRACKING_ACTIVE",
                            "GPS_CONFIRMED", "SERVER_ACK" -> {
                        if (!stages.contains(normalized)) {
                            stages.add(normalized);
                        }
                        lastStage = row;
                    }
                    default -> {
                        // BLOCKED/SKIPPED/ACK_REJECTED: no son tipos del timeline.
                    }
                }
            }
            boolean serverAck = stages.contains("SERVER_ACK");
            boolean gpsConfirmed = stages.contains("GPS_CONFIRMED");

            if (attempt != null) {
                entries.add(new Entry(attempt.timeMs(), TYPE_RECOVERY_ATTEMPT, SEVERITY_INFO,
                        null, recoveryEvidence(attempt, stages), SOURCE_RECOVERY, CONFIDENCE_HIGH));
            }
            if (success != null) {
                entries.add(new Entry(success.timeMs(), TYPE_RECOVERY_SUCCESS, SEVERITY_INFO,
                        null, recoveryEvidence(success, stages), SOURCE_RECOVERY, CONFIDENCE_HIGH));
            } else if (timeout == null && (serverAck || gpsConfirmed) && lastStage != null) {
                // Recuperación real sin evento SUCCESS explícito: SERVER_ACK es
                // evidencia del servidor (llegó una posición tras el probe);
                // GPS_CONFIRMED es del cliente → confianza MEDIUM.
                Map<String, Object> evidence = recoveryEvidence(lastStage, stages);
                evidence.put("evidence", serverAck ? "SERVER_ACK" : "GPS_CONFIRMED");
                entries.add(new Entry(lastStage.timeMs(), TYPE_RECOVERY_SUCCESS, SEVERITY_INFO,
                        null, evidence, SOURCE_RECOVERY,
                        serverAck ? CONFIDENCE_HIGH : CONFIDENCE_MEDIUM));
            }
            if (timeout != null) {
                entries.add(new Entry(timeout.timeMs(), TYPE_RECOVERY_TIMEOUT, SEVERITY_WARNING,
                        null, recoveryEvidence(timeout, stages), SOURCE_RECOVERY, CONFIDENCE_HIGH));
            }
        }
    }

    private static String normalizeRecoveryType(String eventType) {
        if (eventType == null) {
            return "";
        }
        return eventType.startsWith("RECOVERY_") ? eventType.substring("RECOVERY_".length()) : eventType;
    }

    private static Map<String, Object> recoveryEvidence(RecoveryRecord row, List<String> stages) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("eventType", row.eventType());
        evidence.put("time", row.timeMs());
        if (row.attemptId() != null && !row.attemptId().isBlank()) {
            evidence.put("attemptId", row.attemptId());
        }
        if (row.reason() != null && !row.reason().isBlank()) {
            evidence.put("reason", row.reason());
        }
        if (row.source() != null && !row.source().isBlank()) {
            evidence.put("source", row.source());
        }
        if (!stages.isEmpty()) {
            evidence.put("stages", List.copyOf(stages));
        }
        return evidence;
    }

    // ------------------------------------------------------------------
    // tc_device_health: FGS_STATE y OUTBOX_BACKLOG
    // ------------------------------------------------------------------

    private static void addHealthEntries(List<Entry> entries, List<HealthRecord> healthRows,
            long from, long to) {
        List<HealthRecord> ordered = new ArrayList<>();
        if (healthRows != null) {
            for (HealthRecord row : healthRows) {
                if (row != null && row.timeMs() > 0) {
                    ordered.add(row);
                }
            }
        }
        ordered.sort(Comparator.comparingLong(HealthRecord::timeMs));

        HealthRecord previous = null;
        Long backlogStart = null;
        int backlogSamples = 0;
        int startOutbox = 0;
        int maxOutbox = 0;

        for (HealthRecord row : ordered) {
            if (row.outbox() != null) {
                if (row.outbox() > 0) {
                    if (backlogStart == null) {
                        backlogStart = row.timeMs();
                        startOutbox = row.outbox();
                        maxOutbox = row.outbox();
                        backlogSamples = 1;
                    } else {
                        maxOutbox = Math.max(maxOutbox, row.outbox());
                        backlogSamples++;
                    }
                } else if (backlogStart != null) {
                    addBacklogEntry(entries, backlogStart, row.timeMs(), startOutbox, maxOutbox,
                            backlogSamples, from, to, false);
                    backlogStart = null;
                }
            }
            boolean firstOrChanged = previous == null || previous.fgs() == null
                    || !Objects.equals(row.fgs(), previous.fgs());
            if (row.fgs() != null && firstOrChanged) {
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("fgs", row.fgs());
                if (previous != null && previous.fgs() != null) {
                    evidence.put("previousFgs", previous.fgs());
                }
                evidence.put("eventType", row.eventType());
                evidence.put("time", row.timeMs());
                if (row.reason() != null && !row.reason().isBlank()) {
                    evidence.put("reason", row.reason());
                }
                if (row.healthState() != null) {
                    evidence.put("healthState", row.healthState());
                }
                if (row.motion() != null) {
                    evidence.put("motion", row.motion());
                }
                if (row.network() != null) {
                    evidence.put("network", row.network());
                }
                entries.add(new Entry(row.timeMs(), TYPE_FGS_STATE,
                        row.fgs() ? SEVERITY_INFO : SEVERITY_WARNING, null, evidence,
                        SOURCE_HEALTH, CONFIDENCE_HIGH));
            }
            previous = row;
        }
        if (backlogStart != null) {
            addBacklogEntry(entries, backlogStart, 0, startOutbox, maxOutbox, backlogSamples,
                    from, to, true);
        }
    }

    private static void addBacklogEntry(List<Entry> entries, long startMs, long endMs,
            int startOutbox, int maxOutbox, int samples, long from, long to, boolean ongoing) {
        if (startMs > to || (!ongoing && endMs < from)) {
            return;
        }
        long visibleStart = Math.max(startMs, from);
        boolean clipped = startMs < from;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("startOutbox", startOutbox);
        evidence.put("maxOutbox", maxOutbox);
        evidence.put("samples", samples);
        evidence.put("clippedToWindow", clipped);
        if (!ongoing) {
            evidence.put("clearedAt", endMs);
        } else {
            evidence.put("ongoing", true);
        }
        entries.add(new Entry(visibleStart, TYPE_OUTBOX_BACKLOG, SEVERITY_WARNING,
                ongoing ? null : Math.max(0, endMs - startMs), evidence,
                SOURCE_HEALTH, CONFIDENCE_HIGH));
    }

    // ------------------------------------------------------------------
    // tc_positions: CAPTURE_GAP (huecos) y DELIVERY_DELAY (servertime-fixtime)
    // ------------------------------------------------------------------

    private static List<PositionSample> sortedSamples(List<PositionSample> positionTimes) {
        List<PositionSample> ordered = new ArrayList<>();
        if (positionTimes != null) {
            for (PositionSample sample : positionTimes) {
                if (sample != null && sample.fixTimeMs() > 0) {
                    ordered.add(sample);
                }
            }
        }
        ordered.sort(Comparator.comparingLong(PositionSample::fixTimeMs));
        return ordered;
    }

    /** Devuelve los huecos [inicio, fin] detectados (para deduplicar mobileStalled). */
    private static List<long[]> addCaptureGaps(List<Entry> entries, List<PositionSample> samples,
            long from, long to) {
        List<long[]> gaps = new ArrayList<>();
        if (samples.isEmpty()) {
            // Sin ningún fix no se puede distinguir captura de dispositivo apagado:
            // no se declara CAPTURE_GAP (se emitirá UNKNOWN si no hay otra evidencia).
            return gaps;
        }
        long first = samples.get(0).fixTimeMs();
        if (first - from > CAPTURE_GAP_THRESHOLD_MS) {
            appendGap(entries, gaps, from, first, true);
        }
        for (int index = 1; index < samples.size(); index++) {
            long previous = samples.get(index - 1).fixTimeMs();
            long current = samples.get(index).fixTimeMs();
            if (current - previous <= CAPTURE_GAP_THRESHOLD_MS) {
                continue;
            }
            long start = Math.max(previous, from);
            long end = Math.min(current, to);
            boolean clipped = previous < from || current > to;
            appendGap(entries, gaps, start, end, clipped);
        }
        long last = samples.get(samples.size() - 1).fixTimeMs();
        if (to - last > CAPTURE_GAP_THRESHOLD_MS) {
            appendGap(entries, gaps, last, to, true);
        }
        return gaps;
    }

    private static void appendGap(List<Entry> entries, List<long[]> gaps, long start, long end,
            boolean clipped) {
        gaps.add(new long[] {start, end});
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("gapStart", start);
        evidence.put("gapEnd", end);
        evidence.put("clippedToWindow", clipped);
        evidence.put("thresholdMs", CAPTURE_GAP_THRESHOLD_MS);
        entries.add(new Entry(start, TYPE_CAPTURE_GAP, SEVERITY_WARNING, Math.max(0, end - start),
                evidence, SOURCE_POSITIONS, clipped ? CONFIDENCE_MEDIUM : CONFIDENCE_HIGH));
    }

    private static boolean insideAnyGap(List<long[]> gaps, long timeMs) {
        for (long[] gap : gaps) {
            if (timeMs >= gap[0] && timeMs <= gap[1]) {
                return true;
            }
        }
        return false;
    }

    private static void addDeliveryDelays(List<Entry> entries, List<PositionSample> samples,
            long from, long to) {
        int index = 0;
        while (index < samples.size()) {
            PositionSample sample = samples.get(index);
            long delay = sample.serverTimeMs() - sample.fixTimeMs();
            if (!inWindow(sample.fixTimeMs(), from, to)
                    || delay < DELIVERY_DELAY_THRESHOLD_MS) {
                index++;
                continue;
            }
            int endIndex = index;
            long maxDelay = delay;
            while (endIndex + 1 < samples.size()) {
                PositionSample next = samples.get(endIndex + 1);
                long nextDelay = next.serverTimeMs() - next.fixTimeMs();
                if (nextDelay < DELIVERY_DELAY_THRESHOLD_MS) {
                    break;
                }
                maxDelay = Math.max(maxDelay, nextDelay);
                endIndex++;
            }
            boolean closed = endIndex + 1 < samples.size();
            Long duration = closed
                    ? Math.max(0, samples.get(endIndex + 1).fixTimeMs() - sample.fixTimeMs())
                    : null;
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("maxDelayMs", maxDelay);
            evidence.put("samples", endIndex - index + 1);
            evidence.put("firstFixTime", sample.fixTimeMs());
            evidence.put("lastFixTime", samples.get(endIndex).fixTimeMs());
            evidence.put("latestServerTime", samples.get(endIndex).serverTimeMs());
            evidence.put("thresholdMs", DELIVERY_DELAY_THRESHOLD_MS);
            if (closed) {
                evidence.put("firstTimelyFixTime", samples.get(endIndex + 1).fixTimeMs());
            } else {
                evidence.put("ongoing", true);
            }
            entries.add(new Entry(sample.fixTimeMs(), TYPE_DELIVERY_DELAY,
                    maxDelay >= DELIVERY_DELAY_CRITICAL_MS ? SEVERITY_CRITICAL : SEVERITY_WARNING,
                    duration, evidence, SOURCE_POSITIONS, CONFIDENCE_HIGH));
            index = endIndex + 1;
        }
    }

    private static boolean inWindow(long value, long from, long to) {
        return value >= from && value <= to;
    }

    // ------------------------------------------------------------------
    // Lectura (solo SELECT) de la evidencia persistida
    // ------------------------------------------------------------------

    /** Lee las cuatro fuentes y construye el timeline. Nunca lanza. */
    public List<Entry> collect(long deviceId, long fromMs, long toMs) {
        long from = Math.min(fromMs, toMs);
        long to = Math.max(fromMs, toMs);
        List<EventRecord> events = loadEvents(deviceId, from, to);
        List<RecoveryRecord> recoveryEvents = loadRecovery(deviceId, from, to);
        List<HealthRecord> healthRows = loadHealth(deviceId, from, to);
        List<PositionSample> positions = loadPositions(deviceId, from, to);
        return build(events, recoveryEvents, healthRows, positions, from, to);
    }

    private List<EventRecord> loadEvents(long deviceId, long from, long to) {
        List<EventRecord> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(EVENTS_SQL)) {
            statement.setLong(1, deviceId);
            statement.setTimestamp(2, new Timestamp(from));
            statement.setTimestamp(3, new Timestamp(to));
            statement.setInt(4, MAX_DB_ROWS);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Timestamp time = rs.getTimestamp("eventtime");
                    if (time != null) {
                        result.add(new EventRecord(time.getTime(), rs.getString("type"),
                                parseAttributes(rs.getString("attributes"))));
                    }
                }
            }
        } catch (Exception error) {
            LOGGER.warn("Timeline events query failed for device {}: {}", deviceId, error.getMessage());
        }
        return result;
    }

    private List<RecoveryRecord> loadRecovery(long deviceId, long from, long to) {
        List<RecoveryRecord> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(RECOVERY_SQL)) {
            statement.setLong(1, deviceId);
            statement.setTimestamp(2, new Timestamp(from));
            statement.setTimestamp(3, new Timestamp(to));
            statement.setInt(4, MAX_DB_ROWS);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Timestamp time = rs.getTimestamp("ts");
                    if (time != null) {
                        result.add(new RecoveryRecord(time.getTime(), rs.getString("eventtype"),
                                rs.getString("reason"), rs.getString("attemptid"), rs.getString("source")));
                    }
                }
            }
        } catch (Exception error) {
            LOGGER.warn("Timeline recovery query failed for device {}: {}", deviceId, error.getMessage());
        }
        return result;
    }

    private List<HealthRecord> loadHealth(long deviceId, long from, long to) {
        List<HealthRecord> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement baseline = connection.prepareStatement(HEALTH_BASELINE_SQL)) {
                baseline.setLong(1, deviceId);
                baseline.setTimestamp(2, new Timestamp(from));
                try (ResultSet rs = baseline.executeQuery()) {
                    if (rs.next()) {
                        result.add(readHealth(rs));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(HEALTH_SQL)) {
                statement.setLong(1, deviceId);
                statement.setTimestamp(2, new Timestamp(from));
                statement.setTimestamp(3, new Timestamp(to));
                statement.setInt(4, MAX_DB_ROWS);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        result.add(readHealth(rs));
                    }
                }
            }
        } catch (Exception error) {
            LOGGER.warn("Timeline health query failed for device {}: {}", deviceId, error.getMessage());
        }
        return result;
    }

    private static HealthRecord readHealth(ResultSet rs) throws Exception {
        Boolean fgs = rs.getObject("fgs") == null ? null : rs.getBoolean("fgs");
        Object outbox = rs.getObject("outbox");
        return new HealthRecord(
                rs.getTimestamp("ts").getTime(),
                rs.getString("eventtype"),
                rs.getString("reason"),
                rs.getString("healthstate"),
                fgs,
                rs.getString("motion"),
                rs.getString("network"),
                outbox instanceof Number number ? number.intValue() : null);
    }

    private List<PositionSample> loadPositions(long deviceId, long from, long to) {
        List<PositionSample> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement baseline = connection.prepareStatement(POSITION_BASELINE_SQL)) {
                baseline.setLong(1, deviceId);
                baseline.setTimestamp(2, new Timestamp(from));
                try (ResultSet rs = baseline.executeQuery()) {
                    if (rs.next()) {
                        result.add(readPosition(rs));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(POSITIONS_SQL)) {
                statement.setLong(1, deviceId);
                statement.setTimestamp(2, new Timestamp(from));
                statement.setTimestamp(3, new Timestamp(to));
                statement.setInt(4, MAX_DB_ROWS);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        result.add(readPosition(rs));
                    }
                }
            }
        } catch (Exception error) {
            LOGGER.warn("Timeline positions query failed for device {}: {}", deviceId, error.getMessage());
        }
        return result;
    }

    private static PositionSample readPosition(ResultSet rs) throws Exception {
        Timestamp fix = rs.getTimestamp("fixtime");
        Timestamp server = rs.getTimestamp("servertime");
        return new PositionSample(fix == null ? 0 : fix.getTime(),
                server == null ? 0 : server.getTime());
    }

    /** JSON de attributes (VARCHAR) → mapa de escalares; nunca lanza. */
    private Map<String, Object> parseAttributes(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) {
                return Map.of();
            }
            Map<String, Object> attributes = new LinkedHashMap<>();
            root.properties().forEach(entry -> {
                if (attributes.size() >= MAX_ATTRIBUTE_KEYS) {
                    return;
                }
                JsonNode value = entry.getValue();
                if (value == null || value.isNull() || value.isContainerNode()) {
                    return;
                }
                if (value.isBoolean()) {
                    attributes.put(entry.getKey(), value.booleanValue());
                } else if (value.isNumber()) {
                    attributes.put(entry.getKey(), value.numberValue());
                } else {
                    attributes.put(entry.getKey(), value.asText());
                }
            });
            return attributes;
        } catch (Exception error) {
            return Map.of();
        }
    }

    // ------------------------------------------------------------------
    // Utilidades de la capa HTTP
    // ------------------------------------------------------------------

    /** ISO-8601 (Z/offset/local) o epoch ms/segundos. Devuelve fallback si no parsea. */
    public static long parseTime(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim();
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0) {
                return parsed < 100_000_000_000L ? parsed * 1000L : parsed;
            }
        } catch (NumberFormatException notEpoch) {
            // sigue con ISO
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (Exception ignored) {
            // sigue con offset/local
        }
        try {
            return OffsetDateTime.parse(value).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            // sigue con local
        }
        try {
            return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static int clampLimit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(requested, MAX_LIMIT));
    }
}
