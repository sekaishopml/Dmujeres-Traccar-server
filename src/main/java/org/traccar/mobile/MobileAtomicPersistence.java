/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.traccar.mobile;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.model.MobileMessage;
import org.traccar.model.Position;
import org.traccar.storage.QueryBuilder;
import org.traccar.storage.query.Columns;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Persists a mobile position and its deduplication record in a single JDBC transaction.
 *
 * <p>The reservation is claimed first (lease), then the position insert and the
 * {@code tc_mobile_messages} transition to {@code accepted} happen atomically.
 * A crash before commit rolls everything back; a crash after commit leaves the message
 * {@code accepted} so redelivery returns {@code duplicate} without a second position.
 */
@Singleton
public class MobileAtomicPersistence {

    private static final Logger LOGGER = LoggerFactory.getLogger(MobileAtomicPersistence.class);

    private final Config config;
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    @Inject
    public MobileAtomicPersistence(Config config, DataSource dataSource, ObjectMapper objectMapper) {
        this.config = config;
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    /**
     * Claims an expired {@code processing} reservation with a fresh lease. Returns the new
     * lease token if claimed, or {@code null} if the message is still owned by another worker.
     */
    public String claim(MobileMessage message, long leaseDurationMs) {
        String token = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE tc_mobile_messages SET leaseuntil = ?, leasetoken = ?, attempts = attempts + 1, "
                                + "updated = ? WHERE id = ? AND status = 'processing' "
                                + "AND (leaseuntil IS NULL OR leaseuntil < ?)")) {
            statement.setTimestamp(1, new Timestamp(now + leaseDurationMs));
            statement.setString(2, token);
            statement.setTimestamp(3, new Timestamp(now));
            statement.setLong(4, message.getId());
            statement.setTimestamp(5, new Timestamp(now));
            if (statement.executeUpdate() == 1) {
                message.setLeaseToken(token);
                message.setLeaseUntil(new Date(now + leaseDurationMs));
                message.setAttempts(message.getAttempts() + 1);
                return token;
            }
        } catch (SQLException error) {
            LOGGER.warn("Failed to claim mobile message {}", message.getMessageId(), error);
        }
        return null;
    }

    /**
     * Persists the position and completes the message atomically. Returns {@code true} only
     * after the transaction committed; returns {@code false} on failure or lost lease.
     */
    public boolean persist(MobileMessage message, Position position) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<String> columns = new Columns.Exclude("id").getColumns(Position.class, "get");
                long positionId;
                try (QueryBuilder builder = QueryBuilder.create(
                        config, connection, objectMapper, insertSql("tc_positions", columns), true)) {
                    builder.setObject(position, columns);
                    positionId = builder.executeUpdate();
                }
                if (positionId <= 0) {
                    throw new SQLException("position insert returned no generated key");
                }
                position.setId(positionId);

                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE tc_mobile_messages SET positionid = ?, status = 'accepted', "
                                + "leaseuntil = NULL, leasetoken = NULL, updated = ? "
                                + "WHERE id = ? AND status = 'processing' AND leasetoken = ?")) {
                    statement.setLong(1, positionId);
                    statement.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
                    statement.setLong(3, message.getId());
                    statement.setString(4, message.getLeaseToken());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("mobile message lease lost");
                    }
                }

                connection.commit();
                return true;
            } catch (SQLException | RuntimeException error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    LOGGER.warn("Mobile atomic rollback failed", rollbackError);
                }
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException error) {
            LOGGER.warn("Mobile atomic persist failed for {}", position.getProtocol(), error);
            return false;
        }
    }

    private static String insertSql(String table, List<String> columns) {
        StringBuilder query = new StringBuilder("INSERT INTO ");
        query.append(table).append("(");
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) {
                query.append(", ");
            }
            query.append(columns.get(index));
        }
        query.append(") VALUES (");
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) {
                query.append(", ");
            }
            query.append("?");
        }
        query.append(")");
        return query.toString();
    }
}
