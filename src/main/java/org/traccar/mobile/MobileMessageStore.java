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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;

@Singleton
public class MobileMessageStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(MobileMessageStore.class);

    public enum Reservation { RESERVED, DUPLICATE, PROCESSING, REJECTED }

    public record Result(Reservation reservation, MobileMessage message) {}

    private final Storage storage;

    @Inject
    public MobileMessageStore(Storage storage) {
        this.storage = storage;
    }

    /**
     * Reserves a message as {@code processing} with a lease, or classifies an existing one.
     * The lease fields are set here; claiming an expired {@code processing} row is done by
     * {@link MobileAtomicPersistence#claim}.
     */
    public Result reserve(long deviceId, MobileEnvelope envelope, byte[] payload) {
        String payloadHash;
        try {
            payloadHash = hash(payload);
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
                        return classify(existing, deviceId, envelope, payloadHashOrNull(payload));
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

    private static String payloadHashOrNull(byte[] payload) {
        try {
            return hash(payload);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    public void complete(MobileMessage message, long positionId) throws StorageException {
        message.setPositionId(positionId);
        message.setStatus("accepted");
        message.setLeaseUntil(null);
        message.setLeaseToken(null);
        message.setUpdated(new Date());
        storage.updateObject(message, new Request(
                new Columns.Exclude("id"), new Condition.Equals("id", message.getId())));
    }

    public void completeWithoutPosition(MobileMessage message) throws StorageException {
        message.setPositionId(0);
        message.setStatus("accepted");
        message.setLeaseUntil(null);
        message.setLeaseToken(null);
        message.setUpdated(new Date());
        storage.updateObject(message, new Request(
                new Columns.Exclude("id"), new Condition.Equals("id", message.getId())));
    }

    public void reject(MobileMessage message) throws StorageException {
        message.setStatus("rejected");
        message.setLeaseUntil(null);
        message.setLeaseToken(null);
        message.setUpdated(new Date());
        storage.updateObject(message, new Request(
                new Columns.Exclude("id"), new Condition.Equals("id", message.getId())));
    }

    private static String hash(byte[] payload) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest) {
            result.append("%02x".formatted(value & 0xff));
        }
        return result.toString();
    }
}
