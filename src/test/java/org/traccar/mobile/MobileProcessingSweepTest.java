/* Copyright 2026 Anton Tananaev (anton@traccar.org) */
package org.traccar.mobile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.database.NotificationManager;
import org.traccar.storage.Storage;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Barrido de tc_mobile_messages con status='processing' huérfanas (crash del
 * server a mitad de proceso): la fila secuestra la clave (deviceId, sequence)
 * de dedupe para siempre si el cliente no reenvía el mismo messageId, así que
 * el ciclo del monitor la elimina cuando el lease venció hace más de grace.
 * accepted/rejected/expired NO se tocan (evidencia).
 */
public class MobileProcessingSweepTest {

    private Storage storage;
    private DataSource dataSource;
    private Connection connection;
    private PreparedStatement statement;

    @BeforeEach
    public void setUp() throws Exception {
        storage = mock(Storage.class);
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(contains("DELETE FROM tc_mobile_messages"))).thenReturn(statement);
    }

    @Test
    public void testSweepDeletesOnlyProcessingRowsOlderThanGrace() throws Exception {
        when(statement.executeUpdate()).thenReturn(3);

        monitor().sweepProcessingForTest(System.currentTimeMillis());

        verify(connection).prepareStatement(contains("status = 'processing'"));
        verify(statement).setTimestamp(anyInt(), any(Timestamp.class));
        verify(statement).executeUpdate();
    }

    @Test
    public void testSweepUsesConfiguredGrace() throws Exception {
        Config config = new Config();
        config.setString(Keys.MOBILE_MQTT_PROCESSING_SWEEP_GRACE_SECONDS, "120");
        MobileSilenceMonitor configured = new MobileSilenceMonitor(storage,
                mock(NotificationManager.class), config, dataSource,
                mock(MobileJourneyRegistry.class), mock(MobileQualityFilter.class),
                mock(MobilePresenceTracker.class), mock(MobileIngestionService.class), null);
        when(statement.executeUpdate()).thenReturn(0);

        long now = System.currentTimeMillis();
        configured.sweepProcessingForTest(now);

        ArgumentCaptor<Timestamp> captor = ArgumentCaptor.forClass(Timestamp.class);
        verify(statement).setTimestamp(anyInt(), captor.capture());
        // cutoff = now - 120 s (grace configurado): margen de 5 s por el reloj.
        long expectedCutoff = now - 120_000L;
        assertTrue(Math.abs(captor.getValue().getTime() - expectedCutoff) < 5_000);
    }

    @Test
    public void testSweepQueryTargetsOnlyProcessingWithLease() throws Exception {
        when(statement.executeUpdate()).thenReturn(0);

        monitor().sweepProcessingForTest(System.currentTimeMillis());

        verify(connection).prepareStatement(contains(
                "status = 'processing' AND leaseuntil IS NOT NULL AND leaseuntil < ?"));
        verify(connection).prepareStatement(contains("DELETE FROM tc_mobile_messages"));
    }

    private MobileSilenceMonitor monitor() {
        return new MobileSilenceMonitor(storage, mock(NotificationManager.class), new Config(),
                dataSource, mock(MobileJourneyRegistry.class), mock(MobileQualityFilter.class),
                mock(MobilePresenceTracker.class), mock(MobileIngestionService.class),
                mock(FcmRecoveryService.class));
    }
}
