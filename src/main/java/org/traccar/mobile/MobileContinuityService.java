package org.traccar.mobile;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * FASE 5 (§6, §40, §41): continuidad por jornada/sesión leída de las posiciones
 * reales. Usa {@code mobile.journeyId} cuando la posición lo trae (aditivo desde
 * 1.1.3) y cae a {@code mobile.sessionId} para datos históricos.
 *
 * Devuelve SIEMPRE las fórmulas y sus denominadores: continuationPct y
 * gapThresholdMs viajan explícitos para que el dashboard no los invente.
 * No escribe nada: solo lectura de evidencia.
 */
@Singleton
public class MobileContinuityService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MobileContinuityService.class);

    private final DataSource dataSource;

    @Inject
    public MobileContinuityService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public record JourneyContinuity(
            Long journeyId,
            String sessionId,
            MobileContinuityPolicy.Summary summary,
            long serverReceivedFirstMs,
            long serverReceivedLastMs) {}

    /**
     * Continuidad de una jornada concreta (journeyId = mobile.journeyId).
     * Adjunta además el resumen por sesión dentro de la jornada.
     */
    public JourneyContinuity byJourney(long deviceId, long journeyId) {
        String sql = "SELECT devicetime, attributes::jsonb ->> 'mobile.sessionId' AS sessionid, "
                + "attributes::jsonb ->> 'mobile.journeyId' AS journeyid, "
                + "attributes::jsonb ->> 'serverReceivedAt' AS receivedat "
                + "FROM tc_positions WHERE deviceid = ? "
                + "AND attributes::jsonb ->> 'mobile.journeyId' = ? ORDER BY devicetime ASC";
        List<Long> times = new ArrayList<>();
        Map<String, List<Long>> perSession = new TreeMap<>();
        String sessionId = null;
        long firstReceived = 0;
        long lastReceived = 0;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deviceId);
            statement.setString(2, Long.toString(journeyId));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    long fixMs = rs.getTimestamp("devicetime").getTime();
                    times.add(fixMs);
                    String session = rs.getString("sessionid");
                    if (session != null) {
                        sessionId = session;
                        perSession.computeIfAbsent(session, key -> new ArrayList<>()).add(fixMs);
                    }
                    long received = parseReceived(rs.getString("receivedat"));
                    if (received > 0) {
                        if (firstReceived == 0 || received < firstReceived) {
                            firstReceived = received;
                        }
                        lastReceived = Math.max(lastReceived, received);
                    }
                }
            }
        } catch (Exception error) {
            LOGGER.warn("Continuity query failed for device {} journey {}", deviceId, journeyId, error);
            return new JourneyContinuity(journeyId, null,
                    MobileContinuityPolicy.compute(List.of()), 0, 0);
        }
        List<MobileContinuityPolicy.SessionSpan> spans = new ArrayList<>();
        perSession.forEach((id, sessionTimes) -> {
            MobileContinuityPolicy.Summary s = MobileContinuityPolicy.compute(sessionTimes);
            spans.add(new MobileContinuityPolicy.SessionSpan(id, s.firstMs(), s.lastMs(), s.fixes()));
        });
        return new JourneyContinuity(
                journeyId, sessionId, MobileContinuityPolicy.compute(times, spans),
                firstReceived, lastReceived);
    }

    /**
     * Continuidad de la última jornada con evidencia en el rango pedido (o de
     * la sesión indicada). Ordena por journeyId/sessionId más reciente.
     */
    public List<JourneyContinuity> recent(long deviceId, long fromMs, long toMs, int limit) {
        String sql = "SELECT attributes::jsonb ->> 'mobile.journeyId' AS journeyid, "
                + "attributes::jsonb ->> 'mobile.sessionId' AS sessionid, "
                + "min(devicetime) AS firstfix, max(devicetime) AS lastfix, count(*) AS fixes "
                + "FROM tc_positions WHERE deviceid = ? AND devicetime >= ? AND devicetime <= ? "
                + "GROUP BY 1, 2 ORDER BY max(devicetime) DESC LIMIT ?";
        List<String> journeyIds = new ArrayList<>();
        List<String> sessionIds = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deviceId);
            statement.setTimestamp(2, new java.sql.Timestamp(fromMs));
            statement.setTimestamp(3, new java.sql.Timestamp(toMs));
            statement.setInt(4, Math.max(1, Math.min(limit, 50)));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    journeyIds.add(rs.getString("journeyid"));
                    sessionIds.add(rs.getString("sessionid"));
                }
            }
        } catch (Exception error) {
            LOGGER.warn("Recent continuity query failed for device {}", deviceId, error);
            return List.of();
        }
        List<JourneyContinuity> result = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (int index = 0; index < journeyIds.size(); index++) {
            String key = (journeyIds.get(index) != null ? "j:" + journeyIds.get(index) : "s:" + sessionIds.get(index));
            if (seen.putIfAbsent(key, Boolean.TRUE) != null) {
                continue;
            }
            if (journeyIds.get(index) != null) {
                result.add(byJourney(deviceId, Long.parseLong(journeyIds.get(index))));
            } else if (sessionIds.get(index) != null) {
                result.add(bySession(deviceId, sessionIds.get(index)));
            }
        }
        return result;
    }

    /** Continuidad de una sesión histórica (sin journeyId en posiciones). */
    public JourneyContinuity bySession(long deviceId, String sessionId) {
        String sql = "SELECT devicetime FROM tc_positions WHERE deviceid = ? "
                + "AND attributes::jsonb ->> 'mobile.sessionId' = ? ORDER BY devicetime ASC";
        List<Long> times = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deviceId);
            statement.setString(2, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    times.add(rs.getTimestamp("devicetime").getTime());
                }
            }
        } catch (Exception error) {
            LOGGER.warn("Session continuity query failed for device {} session {}", deviceId, sessionId, error);
            return new JourneyContinuity(null, sessionId, MobileContinuityPolicy.compute(List.of()), 0, 0);
        }
        return new JourneyContinuity(null, sessionId, MobileContinuityPolicy.compute(times), 0, 0);
    }

    private static long parseReceived(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return java.time.Instant.parse(value).toEpochMilli();
        } catch (Exception error) {
            return 0;
        }
    }
}
