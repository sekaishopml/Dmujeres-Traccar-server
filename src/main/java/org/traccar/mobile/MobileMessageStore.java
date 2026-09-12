/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.traccar.mobile;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.MobileMessage;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;

@Singleton
public class MobileMessageStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(MobileMessageStore.class);

    public enum Reservation { RESERVED, DUPLICATE, PROCESSING, REJECTED }

    public record Result(Reservation reservation, MobileMessage message) {}

    private final Storage storage;
    private final DataSource dataSource;

    @Inject
    public MobileMessageStore(Storage storage, DataSource dataSource) {
        this.storage = storage;
        this.dataSource = dataSource;
    }

    /**
     * Reserves a message as {@code processing} with a lease, or classifies an existing one.
     * The lease fields are set here; claiming an expired {@code processing} row is done by
     * {@link MobileAtomicPersistence#claim}.
     */
    public Result reserve(long deviceId, MobileEnvelope envelope, String payloadHash) {
        try {
            MobileMessage existing = findExisting(deviceId, envelope);
            if (existing != null) {
                return classify(existing, deviceId, envelope, payloadHash);
            }

            MobileMessage message = new MobileMessage();
            message.setDeviceId(deviceId);
            message.setMessageId(envelope.getMessageId());
            message.setSequence(envelope.getSequence());
            message.setStatus("processing");
            message.setPayloadHash(payloadHash);
            message.setAttempts(0);
            message.setCreated(new Date());
            message.setUpdated(new Date());
            message.setId(storage.addObject(message, new Request(new Columns.Exclude("id", "positionId"))));
            return new Result(Reservation.RESERVED, message);
        } catch (Exception error) {
            if (isUniqueViolation(error)) {
                try {
                    MobileMessage existing = findExisting(deviceId, envelope);
                    if (existing != null) {
                        LOGGER.info("Mobile dedupe reservation lost a concurrent insert: {}",
                                envelope.getMessageId());
                        return classify(existing, deviceId, envelope, payloadHash);
                    }
                } catch (Exception lookupError) {
                    LOGGER.warn("Failed to resolve concurrent mobile reservation {}",
                            envelope.getMessageId(), lookupError);
                }
            }
            LOGGER.warn("Failed to reserve mobile message {}", envelope.getMessageId(), error);
            return new Result(Reservation.REJECTED, null);
        }
    }

    private MobileMessage findExisting(long deviceId, MobileEnvelope envelope) throws StorageException {
        MobileMessage existing = storage.getObject(MobileMessage.class,
                new Request(new Columns.All(), new Condition.Equals("messageId", envelope.getMessageId())));
        if (existing == null) {
            existing = storage.getObject(MobileMessage.class,
                    new Request(new Columns.All(), new Condition.And(
                            new Condition.Equals("deviceId", deviceId),
                            new Condition.Equals("sequence", envelope.getSequence()))));
        }
        return existing;
    }

    private Result classify(MobileMessage existing, long deviceId, MobileEnvelope envelope, String payloadHash) {
        if (existing.getDeviceId() != deviceId || existing.getSequence() != envelope.getSequence()
                || !payloadHash.equals(existing.getPayloadHash())) {
            LOGGER.warn("Mobile dedupe key reused with a different payload: {}", envelope.getMessageId());
            return new Result(Reservation.REJECTED, existing);
        }
        return switch (existing.getStatus()) {
            case "accepted" -> new Result(Reservation.DUPLICATE, existing);
            case "rejected" -> new Result(Reservation.REJECTED, existing);
            default -> new Result(Reservation.PROCESSING, existing);
        };
    }

    private static boolean isUniqueViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && (message.toLowerCase().contains("unique")
                    || message.toLowerCase().contains("duplicate")
                    || message.toLowerCase().contains("constraint"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finaliza con posición. El UPDATE es condicional (id + status='processing' +
     * leasetoken propio): si otro worker reclamó el lease vencido y ya finalizó el
     * mensaje, este worker tardío se vuelve no-op en vez de DESVINCULAR la posición
     * (el bug clásico: completeWithoutPosition tardío pisaba accepted+positionId con
     * accepted+positionId=0, huérfana en tc_positions). Si el mensaje ya quedó
     * accepted, se acepta el resultado (procesado una sola vez igual); si quedó en
     * otro estado, se lanza para que el llamador devuelva PENDING y el reintento
     * clasifique el estado real (converge al ACK correcto).
     */
    public void complete(MobileMessage message, long positionId) throws StorageException {
        message.setPositionId(positionId);
        message.setStatus("accepted");
        message.setLeaseUntil(null);
        message.setLeaseToken(null);
        message.setUpdated(new Date());
        if (!conditionalFinalize(message, positionId, "accepted")) {
            resolveConflict(message);
        }
    }

    public void completeWithoutPosition(MobileMessage message) throws StorageException {
        message.setPositionId(0);
        message.setStatus("accepted");
        message.setLeaseUntil(null);
        message.setLeaseToken(null);
        message.setUpdated(new Date());
        if (!conditionalFinalize(message, 0, "accepted")) {
            resolveConflict(message);
        }
    }

    public void reject(MobileMessage message) throws StorageException {
        message.setStatus("rejected");
        message.setLeaseUntil(null);
        message.setLeaseToken(null);
        message.setUpdated(new Date());
        if (!conditionalFinalize(message, message.getPositionId(), "rejected")) {
            resolveConflict(message);
        }
    }

    /**
     * UPDATE condicional atómico: solo finaliza si el mensaje sigue en
     * 'processing' con NUESTRO lease. Devuelve true si aplicó.
     */
    boolean conditionalFinalize(MobileMessage message, long positionId, String status) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE tc_mobile_messages SET positionid = ?, status = ?, "
                                + "leaseuntil = NULL, leasetoken = NULL, updated = ? "
                                + "WHERE id = ? AND status = 'processing' AND leasetoken = ?")) {
            statement.setLong(1, positionId);
            statement.setString(2, status);
            statement.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
            statement.setLong(4, message.getId());
            statement.setString(5, message.getLeaseToken());
            return statement.executeUpdate() == 1;
        } catch (SQLException error) {
            LOGGER.warn("Failed to finalize mobile message {}", message.getMessageId(), error);
            return false;
        }
    }

    /**
     * El lease se perdió: relee el estado real. Si otro worker ya lo aceptó, el
     * mensaje se procesó una sola vez y este resultado tardío se absorbe (sin
     * excepción). En cualquier otro caso se lanza para que el llamador responda
     * PENDING y la redelivery clasifique (duplicate/pending) hasta converger.
     */
    void resolveConflict(MobileMessage message) throws StorageException {
        MobileMessage current;
        try {
            current = storage.getObject(MobileMessage.class, new Request(
                    new Columns.All(), new Condition.Equals("id", message.getId())));
        } catch (Exception error) {
            throw new StorageException("failed to resolve mobile message conflict", error);
        }
        if (current != null && "accepted".equals(current.getStatus())) {
            LOGGER.info("Mobile message {} already accepted by concurrent worker; absorbing late finalize",
                    message.getMessageId());
            message.setPositionId(current.getPositionId());
            message.setStatus("accepted");
            return;
        }
        throw new StorageException("mobile message lease lost (status="
                + (current != null ? current.getStatus() : "missing") + "); leaving for retry");
    }
}
