/*
 * Copyright 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.traccar.schedule;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.MobileMessage;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.Date;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Limpieza periódica de mensajes móviles terminales antiguos (retención por defecto: 7 días).
 * Nunca borra mensajes en estado "processing" para no perder trabajos en curso.
 */
public class TaskMobileMessageCleanup extends SingleScheduleTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskMobileMessageCleanup.class);

    private static final long CHECK_PERIOD_HOURS = 24;
    private static final long RETENTION_DAYS = 7;

    private final Storage storage;

    @Inject
    public TaskMobileMessageCleanup(Storage storage) {
        this.storage = storage;
    }

    @Override
    public void schedule(ScheduledExecutorService executor) {
        executor.scheduleAtFixedRate(this, CHECK_PERIOD_HOURS, CHECK_PERIOD_HOURS, TimeUnit.HOURS);
    }

    @Override
    public void run() {
        try {
            Date cutoff = new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS));
            storage.removeObject(MobileMessage.class, new Request(
                    new Condition.And(
                            new Condition.Compare("updated", "<", cutoff),
                            new Condition.Compare("status", "<>", "processing"))));
            LOGGER.info("Mobile message cleanup: deleted terminal messages older than {} days",
                    RETENTION_DAYS);
        } catch (StorageException error) {
            LOGGER.warn("Failed to clean old mobile messages", error);
        }
    }
}
