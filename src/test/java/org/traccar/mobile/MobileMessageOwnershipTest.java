/* Copyright 2026 Anton Tananaev (anton@traccar.org) */
package org.traccar.mobile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traccar.model.MobileMessage;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.query.Request;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Idempotencia bajo concurrencia: el finalize es condicional (id + status +
 * lease propio). Un worker tardío cuyo lease reclamó otro NO desvincula la
 * posición ya aceptada (el bug sería pisar accepted+positionId con
 * accepted+positionId=0); si el mensaje ya quedó accepted se absorbe, y si no,
 * se lanza para que el llamador devuelva PENDING y la redelivery clasifique.
 */
public class MobileMessageOwnershipTest {

    private Storage storage;
    private DataSource dataSource;
    private Connection connection;
    private PreparedStatement statement;
    private MobileMessageStore store;

    @BeforeEach
    public void setUp() throws Exception {
        storage = mock(Storage.class);
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        store = new MobileMessageStore(storage, dataSource);
    }

    @Test
    public void testFinalizeWithLeaseAppliesWithoutReread() throws Exception {
        when(statement.executeUpdate()).thenReturn(1);

        store.completeWithoutPosition(message());

        verify(storage, never()).getObject(eq(MobileMessage.class), any(Request.class));
    }

    @Test
    public void testLateFinalizeAfterConcurrentAcceptIsAbsorbed() throws Exception {
        when(statement.executeUpdate()).thenReturn(0);
        MobileMessage accepted = message();
        accepted.setStatus("accepted");
        accepted.setPositionId(99L);
        when(storage.getObject(eq(MobileMessage.class), any(Request.class))).thenReturn(accepted);

        MobileMessage late = message();
        assertDoesNotThrow(() -> store.completeWithoutPosition(late));

        // Se absorbe el estado ganador: la posición NO se desvincula.
        assertEquals("accepted", late.getStatus());
        assertEquals(99L, late.getPositionId());
    }

    @Test
    public void testLateFinalizeWhileProcessingRetries() throws Exception {
        when(statement.executeUpdate()).thenReturn(0);
        MobileMessage processing = message();
        processing.setStatus("processing");
        when(storage.getObject(eq(MobileMessage.class), any(Request.class))).thenReturn(processing);

        // PENDING → la redelivery (QoS1 / HTTP retry) clasificará duplicate/pending.
        assertThrows(StorageException.class, () -> store.completeWithoutPosition(message()));
    }

    @Test
    public void testLateFinalizeAfterConcurrentRejectRetries() throws Exception {
        when(statement.executeUpdate()).thenReturn(0);
        MobileMessage rejected = message();
        rejected.setStatus("rejected");
        when(storage.getObject(eq(MobileMessage.class), any(Request.class))).thenReturn(rejected);

        // No accepted que absorber: se lanza para converger vía reintento.
        assertThrows(StorageException.class, () -> store.completeWithoutPosition(message()));
    }

    @Test
    public void testRejectUsesConditionalWrite() throws Exception {
        when(statement.executeUpdate()).thenReturn(1);

        MobileMessage message = message();
        store.reject(message);

        assertEquals("rejected", message.getStatus());
        verify(connection).prepareStatement(anyString());
    }

    private static MobileMessage message() {
        MobileMessage message = new MobileMessage();
        message.setId(5L);
        message.setDeviceId(7L);
        message.setMessageId("message-123456789");
        message.setSequence(1L);
        message.setStatus("processing");
        message.setLeaseToken("token-abc");
        return message;
    }
}
