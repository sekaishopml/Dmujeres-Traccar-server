/* Copyright 2026 Anton Tananaev (anton@traccar.org) */
package org.traccar.mobile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.traccar.database.NotificationManager;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.storage.Storage;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tracker de presencia con dependencias mockeadas:
 * - LWT con jornada = SUSPECT (nunca OFFLINE directo).
 * - Cierre = OFFLINE + anti-resurrección por journeyId.
 * - Dimensiones GPS/NETWORK/MQTT/OUTBOX separadas y sin mezclarse.
 * - Transiciones del temporizador con eventos y persistencia solo al cambiar.
 */
public class MobilePresenceTrackerTest {

    private static final long DEVICE_ID = 7L;

    private Storage storage;
    private NotificationManager notifications;
    private MobileJourneyRegistry registry;
    private MobilePresenceTracker tracker;

    @BeforeEach
    public void setUp() {
        storage = mock(Storage.class);
        notifications = mock(NotificationManager.class);
        registry = mock(MobileJourneyRegistry.class);
        // Umbrales de test: suspect 60 s, offline 120 s, gpsStale 60 s, mqttGrace 30 s.
        tracker = new MobilePresenceTracker(
                30_000L, 60_000L, 120_000L, 60_000L, 30_000L, storage, notifications, registry);
    }

    @Test
    public void testArrivalBringsOnlineWithDimensions() throws Exception {
        when(registry.isActive(DEVICE_ID)).thenReturn(true);
        Device device = device();

        tracker.onArrival(device, payload(
                "{\"payload\":{\"network\":\"wifi\",\"validated\":true,\"gps\":\"on\","
                        + "\"pending\":0,\"battery\":80}}"), MobileChannel.MQTT, 1_700_000_000_000L);

        MobilePresenceTracker.PresenceView view = tracker.getView(DEVICE_ID);
        assertEquals(MobilePresenceTracker.PresenceState.ONLINE, view.getState());
        assertEquals(MobilePresenceTracker.GpsState.OK, view.getGps());
        assertEquals(MobilePresenceTracker.NetState.WIFI, view.getNetwork());
        assertEquals(MobilePresenceTracker.MqttState.CONNECTED, view.getMqtt());
        assertEquals(MobilePresenceTracker.OutboxState.EMPTY, view.getOutbox());
        // Primer contacto persiste el estado (una escritura), sin evento.
        verify(storage, times(1)).updateObject(any(Device.class), any());
        verify(notifications, never()).updateEvents(any());
    }

    @Test
    public void testUnvalidatedWifiIsNotInternet() throws Exception {
        when(registry.isActive(DEVICE_ID)).thenReturn(true);
        Device device = device();

        tracker.onArrival(device, payload(
                "{\"payload\":{\"network\":\"wifi\",\"validated\":false,\"pending\":0}}"),
                MobileChannel.MQTT, -1L);

        assertEquals(MobilePresenceTracker.NetState.UNVALIDATED, tracker.getView(DEVICE_ID).getNetwork());
        // ...pero la llegada igual prueba liveness: ONLINE (la dimensión que
        // distingue es NETWORK, no PRESENCE).
        assertEquals(MobilePresenceTracker.PresenceState.ONLINE, tracker.getView(DEVICE_ID).getState());
    }

    @Test
    public void testHttpArrivalDoesNotMoveMqtt() throws Exception {
        when(registry.isActive(DEVICE_ID)).thenReturn(true);
        Device device = device();

        tracker.onArrival(device, payload("{\"payload\":{\"network\":\"mobile\",\"pending\":3}}"),
                MobileChannel.HTTP, 1_700_000_000_001L);

        MobilePresenceTracker.PresenceView view = tracker.getView(DEVICE_ID);
        assertEquals(MobilePresenceTracker.MqttState.UNKNOWN, view.getMqtt());
        assertEquals(MobilePresenceTracker.OutboxState.PENDING, view.getOutbox());
        assertEquals(MobilePresenceTracker.NetState.MOBILE, view.getNetwork());
    }

    @Test
    public void testOutboxDrainingWhenPendingShrinks() throws Exception {
        when(registry.isActive(DEVICE_ID)).thenReturn(true);
        Device device = device();

        tracker.onArrival(device, payload("{\"payload\":{\"network\":\"mobile\",\"pending\":50}}"),
                MobileChannel.MQTT, -1L);
        assertEquals(MobilePresenceTracker.OutboxState.PENDING, tracker.getView(DEVICE_ID).getOutbox());
        tracker.onArrival(device, payload("{\"payload\":{\"network\":\"mobile\",\"pending\":20}}"),
                MobileChannel.MQTT, -1L);
        assertEquals(MobilePresenceTracker.OutboxState.DRAINING, tracker.getView(DEVICE_ID).getOutbox());
        tracker.onArrival(device, payload("{\"payload\":{\"network\":\"mobile\",\"pending\":0}}"),
                MobileChannel.MQTT, -1L);
        assertEquals(MobilePresenceTracker.OutboxState.EMPTY, tracker.getView(DEVICE_ID).getOutbox());
    }

    @Test
    public void testLwtWithJourneyIsSuspectNeverOffline() {
        when(registry.isActive(DEVICE_ID)).thenReturn(true);
        Device device = device();
        device.setStatus(Device.STATUS_ONLINE);

        boolean active = tracker.onLwt(device);

        assertTrue(active);
        MobilePresenceTracker.PresenceView view = tracker.getView(DEVICE_ID);
        assertEquals(MobilePresenceTracker.PresenceState.SUSPECT, view.getState());
        assertEquals(MobilePresenceTracker.REASON_SESSION_LOST, view.getReason());
        assertEquals(MobilePresenceTracker.MqttState.DISCONNECTED, view.getMqtt());
        assertEquals(1L, tracker.getLwtEvents());
        // Evento suspect emitido; el temporizador dirá OFFLINE solo a los 120 s.
        verify(notifications, times(1)).updateEvents(any());
        assertEquals(Event.TYPE_MOBILE_PRESENCE_SUSPECT, eventType());
    }

    @Test
    public void testLwtWithoutJourneyIsIgnored() {
        when(registry.isActive(DEVICE_ID)).thenReturn(false);
        Device device = device();

        boolean active = tracker.onLwt(device);

        assertFalse(active);
        assertNull(tracker.getView(DEVICE_ID));
        verify(notifications, never()).updateEvents(any());
    }

    @Test
    public void testEndedGoesOfflineAndBlocksStaleReopen() {
        when(registry.isActive(DEVICE_ID)).thenReturn(true);
        Device device = device();

        tracker.onEnded(device, 42L);

        MobilePresenceTracker.PresenceView view = tracker.getView(DEVICE_ID);
        assertEquals(MobilePresenceTracker.PresenceState.OFFLINE, view.getState());
        assertEquals(MobilePresenceTracker.REASON_JOURNEY_ENDED, view.getReason());
        assertEquals(42L, device.getAttributes().get(MobilePresenceTracker.ATTR_LAST_ENDED));
        // Sin evento de presencia (el journeyEnded ya lo emite el monitor de telemetría).
        verify(notifications, never()).updateEvents(any());

        // Replay con journeyId viejo: no reabre.
        assertFalse(tracker.onStarted(device, 42L));
        assertFalse(tracker.shouldReopen(DEVICE_ID, "device-123", 41L));
        assertEquals(2L, tracker.getStaleJourneyBlocks());
        // Journey nueva sí reabre.
        assertTrue(tracker.shouldReopen(DEVICE_ID, "device-123", 43L));
        assertTrue(tracker.onStarted(device, 43L));
        assertEquals(MobilePresenceTracker.PresenceState.ONLINE, tracker.getView(DEVICE_ID).getState());
    }

    @Test
    public void testTickTransitionsWithEvents() throws Exception {
        when(registry.isActive(DEVICE_ID)).thenReturn(true);
        Device device = device();
        tracker.onArrival(device, payload("{\"payload\":{\"network\":\"wifi\",\"pending\":0}}"),
                MobileChannel.MQTT, 1_700_000_000_000L);

        // Silencio 61 s → SUSPECT + evento (las reglas idle de MQTT/GPS caen en
        // la misma escritura única).
        MobilePresenceTracker.Transition suspect =
                tracker.evaluate(device, System.currentTimeMillis() + 61_000L);
        assertTrue(suspect.changed());
        assertEquals(MobilePresenceTracker.PresenceState.SUSPECT, suspect.to());
        assertEquals(Event.TYPE_MOBILE_PRESENCE_SUSPECT, eventType());

        // Mismo estado en el siguiente tick: sin escritura ni evento.
        int writes = updateCount();
        tracker.evaluate(device, System.currentTimeMillis() + 61_500L);
        assertEquals(writes, updateCount());

        // Silencio 121 s → OFFLINE + evento.
        MobilePresenceTracker.Transition offline =
                tracker.evaluate(device, System.currentTimeMillis() + 121_000L);
        assertTrue(offline.changed());
        assertEquals(MobilePresenceTracker.PresenceState.OFFLINE, offline.to());
        assertEquals(Event.TYPE_MOBILE_PRESENCE_OFFLINE, eventType());

        // Tráfico fresco → ONLINE + recovered.
        tracker.onArrival(device, payload("{\"payload\":{\"network\":\"wifi\",\"pending\":0}}"),
                MobileChannel.MQTT, -1L);
        assertEquals(MobilePresenceTracker.PresenceState.ONLINE, tracker.getView(DEVICE_ID).getState());
        assertEquals(Event.TYPE_MOBILE_PRESENCE_RECOVERED, eventType());
        assertEquals(1L, tracker.getRecoveredEvents());
    }

    @Test
    public void testGpsStaleOnTickWithoutFixes() throws Exception {
        when(registry.isActive(DEVICE_ID)).thenReturn(true);
        Device device = device();
        // Solo presence (sin fix): gps queda UNKNOWN hasta que el tick lo declare STALE.
        tracker.onArrival(device, payload("{\"payload\":{\"network\":\"wifi\",\"gps\":\"on\",\"pending\":0}}"),
                MobileChannel.MQTT, -1L);
        assertEquals(MobilePresenceTracker.GpsState.UNKNOWN, tracker.getView(DEVICE_ID).getGps());

        tracker.evaluate(device, System.currentTimeMillis() + 61_000L);
        assertEquals(MobilePresenceTracker.GpsState.STALE, tracker.getView(DEVICE_ID).getGps());
    }

    @Test
    public void testGpsOffMeansNoFix() throws Exception {
        when(registry.isActive(DEVICE_ID)).thenReturn(true);
        Device device = device();
        tracker.onArrival(device, payload("{\"payload\":{\"network\":\"wifi\",\"gps\":\"off\",\"pending\":0}}"),
                MobileChannel.MQTT, -1L);
        assertEquals(MobilePresenceTracker.GpsState.NO_FIX, tracker.getView(DEVICE_ID).getGps());
    }

    @Test
    public void testMqttConnectingUntilSettled() throws Exception {
        when(registry.isActive(DEVICE_ID)).thenReturn(true);
        Device device = device();
        // Sesión caída y vuelve a llegar tráfico: CONNECTING hasta el ACK.
        tracker.onLwt(device);
        assertEquals(MobilePresenceTracker.MqttState.DISCONNECTED, tracker.getView(DEVICE_ID).getMqtt());
        tracker.onArrival(device, payload("{\"payload\":{\"network\":\"wifi\",\"pending\":1}}"),
                MobileChannel.MQTT, -1L);
        assertEquals(MobilePresenceTracker.MqttState.CONNECTING, tracker.getView(DEVICE_ID).getMqtt());
        tracker.onSettled(DEVICE_ID, true, MobileChannel.MQTT);
        assertEquals(MobilePresenceTracker.MqttState.CONNECTED, tracker.getView(DEVICE_ID).getMqtt());
    }

    private int updateCount() throws Exception {
        ArgumentCaptor<Device> captor = ArgumentCaptor.forClass(Device.class);
        verify(storage, org.mockito.Mockito.atLeast(0)).updateObject(captor.capture(), any());
        return captor.getAllValues().size();
    }

    private String eventType() {
        ArgumentCaptor<Map<Event, org.traccar.model.Position>> captor =
                ArgumentCaptor.forClass(Map.class);
        verify(notifications, org.mockito.Mockito.atLeastOnce()).updateEvents(captor.capture());
        return captor.getValue().keySet().iterator().next().getType();
    }

    private static Device device() {
        Device device = new Device();
        device.setId(DEVICE_ID);
        device.setUniqueId("device-123");
        device.setStatus(Device.STATUS_ONLINE);
        return device;
    }

    private static com.fasterxml.jackson.databind.JsonNode payload(String value) throws Exception {
        return new ObjectMapper().readTree(value);
    }
}
