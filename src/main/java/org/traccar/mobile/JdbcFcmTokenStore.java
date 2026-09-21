package org.traccar.mobile;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;

/** F2: implementación JDBC de {@link FcmTokenStore} (tc_fcm_tokens). */
@Singleton
public class JdbcFcmTokenStore implements FcmTokenStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcFcmTokenStore.class);

    private final DataSource dataSource;

    @Inject
    public JdbcFcmTokenStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void register(long deviceId, String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // 1) El token pasa a otro dispositivo: se libera del anterior.
                try (PreparedStatement detach = connection.prepareStatement(
                        "DELETE FROM tc_fcm_tokens WHERE token = ? AND deviceid <> ?")) {
                    detach.setString(1, token);
                    detach.setLong(2, deviceId);
                    detach.executeUpdate();
                }
                // 2) Token existente del dispositivo que se rota: conservar como previous.
                try (PreparedStatement rotate = connection.prepareStatement(
                        "UPDATE tc_fcm_tokens SET previous_token = token, active = false, updatedat = ? "
                                + "WHERE deviceid = ? AND active = true AND token <> ?")) {
                    rotate.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
                    rotate.setLong(2, deviceId);
                    rotate.setString(3, token);
                    rotate.executeUpdate();
                }
                // 3) Upsert del token actual (activo, no inválido).
                try (PreparedStatement upsert = connection.prepareStatement(
                        "INSERT INTO tc_fcm_tokens (deviceid, token, active, invalid, createdat, updatedat) "
                                + "VALUES (?, ?, true, false, ?, ?) "
                                + "ON CONFLICT (token) DO UPDATE SET deviceid = EXCLUDED.deviceid, "
                                + "active = true, invalid = false, updatedat = EXCLUDED.updatedat")) {
                    Timestamp now = new Timestamp(System.currentTimeMillis());
                    upsert.setLong(1, deviceId);
                    upsert.setString(2, token);
                    upsert.setTimestamp(3, now);
                    upsert.setTimestamp(4, now);
                    upsert.executeUpdate();
                }
                connection.commit();
            } catch (Exception error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception error) {
            LOGGER.warn("FCM token register failed for device {}: {}", deviceId, error.getMessage());
        }
    }

    @Override
    public String activeToken(long deviceId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT token FROM tc_fcm_tokens WHERE deviceid = ? "
                                + "AND active = true AND invalid = false "
                                + "ORDER BY updatedat DESC LIMIT 1")) {
            statement.setLong(1, deviceId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception error) {
            LOGGER.warn("FCM token lookup failed for device {}: {}", deviceId, error.getMessage());
            return null;
        }
    }

    @Override
    public void invalidate(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE tc_fcm_tokens SET invalid = true, active = false, updatedat = ? WHERE token = ?")) {
            statement.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
            statement.setString(2, token);
            statement.executeUpdate();
        } catch (Exception error) {
            LOGGER.warn("FCM token invalidate failed: {}", error.getMessage());
        }
    }

    @Override
    public TokenInfo info(long deviceId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT token, invalid, updatedat FROM tc_fcm_tokens WHERE deviceid = ? "
                                + "AND active = true ORDER BY updatedat DESC LIMIT 1")) {
            statement.setLong(1, deviceId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return new TokenInfo(true, rs.getBoolean("invalid"),
                            rs.getTimestamp("updatedat").getTime(),
                            FcmTokenStore.tokenPrefix(rs.getString("token")));
                }
                return new TokenInfo(false, false, 0L, "");
            }
        } catch (Exception error) {
            return new TokenInfo(false, false, 0L, "");
        }
    }
}
