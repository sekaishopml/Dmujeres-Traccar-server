/* Copyright 2026 Anton Tananaev (anton@traccar.org) */
package org.traccar.mobile;

import org.junit.jupiter.api.Test;
import org.traccar.model.MobileMessage;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Request;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.when;

public class MobileMessageStoreTest {

    @Test
    public void testExistingProcessingMessageIsNotRejected() throws Exception {
        Storage storage = mock(Storage.class, CALLS_REAL_METHODS);
        MobileMessage existing = message("processing");
        when(storage.getObjectsStream(eq(MobileMessage.class), any(Request.class))).thenReturn(Stream.of(existing));

        MobileMessageStore.Result result = new MobileMessageStore(storage).reserve(7, envelope(), "4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a");

        assertEquals(MobileMessageStore.Reservation.PROCESSING, result.reservation());
    }

    @Test
    public void testUniqueRaceIsClassifiedFromDatabase() throws Exception {
        Storage storage = mock(Storage.class, CALLS_REAL_METHODS);
        MobileMessage existing = message("accepted");
        when(storage.getObjectsStream(eq(MobileMessage.class), any(Request.class)))
                .thenReturn(Stream.empty(), Stream.empty(), Stream.of(existing));
        when(storage.addObject(any(), any())).thenThrow(new StorageException("duplicate key"));

        MobileMessageStore.Result result = new MobileMessageStore(storage).reserve(7, envelope(), "4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a");

        assertEquals(MobileMessageStore.Reservation.DUPLICATE, result.reservation());
    }

    private static MobileEnvelope envelope() {
        MobileEnvelope envelope = new MobileEnvelope();
        envelope.setMessageId("message-123456789");
        envelope.setSequence(1);
        return envelope;
    }

    private static MobileMessage message(String status) {
        MobileMessage message = new MobileMessage();
        message.setDeviceId(7);
        message.setMessageId("message-123456789");
        message.setSequence(1);
        message.setStatus(status);
        message.setPayloadHash("4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a");
        return message;
    }
}
