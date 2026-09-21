package org.traccar.mobile;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;

/** FASE 7: implementación JDBC de {@link DeviceHealthStore} (tc_device_health). */
@Singleton
public class JdbcDeviceHealthStore implements DeviceHealthStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcDeviceHealthStore.class);

    private static final String EXISTS = """
            SELECT 1 FROM tc_device_health WHERE deviceid = ? AND ts = ?
            """;

    private static final String INSERT = """
            INSERT INTO tc_device_health (
                deviceid, ts, sessionid, wallbucket, elapsedms, eventtype, reason,
                manufacturer, model, androidversion, appversion, healthstate, fgs,
                motion, network, outbox, lastfixat, readiness, continuity, continuitycause, recovery,
                attributes
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final DataSource dataSource;

    @Inject
    public JdbcDeviceHealthStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean insert(Snapshot snapshot, DeviceProfile profile) {
        try (Connection connection = dataSource.getConnection()) {
            // Idempotencia explícita (portable): si (deviceid, ts) ya existe no
            // se reinserta. La carrera residual la corta la constraint única.
            try (PreparedStatement exists = connection.prepareStatement(EXISTS)) {
                exists.setLong(1, snapshot.deviceId());
                exists.setTimestamp(2, new Timestamp(snapshot.tsMs()));
                try (ResultSet result = exists.executeQuery()) {
                    if (result.next()) {
                        return false;
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
                int index = 1;
                statement.setLong(index++, snapshot.deviceId());
                statement.setTimestamp(index++, new Timestamp(snapshot.tsMs()));
                statement.setString(index++, snapshot.sessionId());
                if (snapshot.wallBucket() != null) {
                    statement.setLong(index++, snapshot.wallBucket());
                } else {
                    statement.setNull(index++, Types.BIGINT);
                }
                if (snapshot.elapsedMs() != null) {
                    statement.setLong(index++, snapshot.elapsedMs());
                } else {
                    statement.setNull(index++, Types.BIGINT);
                }
                statement.setString(index++, snapshot.eventType());
                statement.setString(index++, snapshot.reason());
                statement.setString(index++, profile != null ? profile.manufacturer() : null);
                statement.setString(index++, profile != null ? profile.model() : null);
                statement.setString(index++, profile != null ? profile.androidVersion() : null);
                statement.setString(index++, profile != null ? profile.appVersion() : null);
                if (snapshot.healthState() != null) {
                    statement.setString(index++, snapshot.healthState());
                } else {
                    statement.setString(index++, profile != null ? profile.healthState() : null);
                }
                statement.setBoolean(index++, snapshot.fgs());
                statement.setString(index++, snapshot.motion());
                statement.setString(index++, snapshot.network());
                statement.setInt(index++, snapshot.outbox());
                if (profile != null && profile.lastFixAtMs() != null) {
                    statement.setTimestamp(index++, new Timestamp(profile.lastFixAtMs()));
                } else {
                    statement.setNull(index++, Types.TIMESTAMP);
                }
                statement.setString(index++, profile != null ? profile.readiness() : null);
                statement.setString(index++, profile != null ? profile.continuity() : null);
                statement.setString(index++, profile != null ? profile.continuityCause() : null);
                statement.setString(index++, profile != null ? profile.recovery() : null);
                // F0: el embudo del bucket viaja en `attributes` (sin tocar el
                // esquema): {"funnel": "{\"rx\":..}"} o null si no vino.
                if (snapshot.funnel() != null && !snapshot.funnel().isBlank()) {
                    statement.setString(index, "{\"funnel\":" + snapshot.funnel() + "}");
                } else {
                    statement.setNull(index, Types.VARCHAR);
                }
                return statement.executeUpdate() > 0;
            }
        } catch (SQLException error) {
            // Carrera entre el SELECT y el INSERT: la constraint única la
            // convierte en duplicado (mismo resultado que el pre-chequeo).
            if (error.getSQLState() != null && error.getSQLState().startsWith("23")) {
                return false;
            }
            LOGGER.warn("Device health insert failed for device {}: {}",
                    snapshot.deviceId(), error.getMessage());
            return false;
        }
    }

    @Override
    public int pruneOlderThan(long cutoffMs) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM tc_device_health WHERE ts < ?")) {
            statement.setTimestamp(1, new Timestamp(cutoffMs));
            return statement.executeUpdate();
        } catch (Exception error) {
            LOGGER.warn("Device health prune failed: {}", error.getMessage());
            return 0;
        }
    }
}
