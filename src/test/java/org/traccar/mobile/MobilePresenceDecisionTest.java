/* Copyright 2026 Anton Tananaev (anton@traccar.org) */
package org.traccar.mobile;

import org.junit.jupiter.api.Test;
import org.traccar.model.Event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Matriz pura de la máquina de presencia (sin DI ni BD):
 * - ONLINE con tráfico fresco, SUSPECT a los 5 min, OFFLINE a los 10 min.
 * - Sin jornada: solo OFFLINE (y se libera la vista).
 * - LWT/ended no pasan por aquí (transicionan directo en el tracker).
 */
public class MobilePresenceDecisionTest {

    private static final long SUSPECT = 300_000L;
    private static final long OFFLINE = 600_000L;

    @Test
    public void testFreshTrafficIsOnline() {
        var decision = MobilePresenceTracker.PresenceDecision.decide(
                MobilePresenceTracker.PresenceState.ONLINE, true, 10_000L, SUSPECT, OFFLINE);
        assertEquals(MobilePresenceTracker.PresenceState.ONLINE, decision.state());
        assertNull(decision.eventType());
        assertFalse(decision.untrack());
    }

    @Test
    public void testSilenceBecomesSuspectAfterFiveMinutes() {
        var decision = MobilePresenceTracker.PresenceDecision.decide(
                MobilePresenceTracker.PresenceState.ONLINE, true, SUSPECT + 1_000L, SUSPECT, OFFLINE);
        assertEquals(MobilePresenceTracker.PresenceState.SUSPECT, decision.state());
        assertEquals(MobilePresenceTracker.REASON_TIMEOUT_SUSPECT, decision.reason());
        assertEquals(Event.TYPE_MOBILE_PRESENCE_SUSPECT, decision.eventType());
        assertFalse(decision.untrack());
    }

    @Test
    public void testSuspectStaysSuspectWithoutNewEvent() {
        var decision = MobilePresenceTracker.PresenceDecision.decide(
                MobilePresenceTracker.PresenceState.SUSPECT, true, SUSPECT + 60_000L, SUSPECT, OFFLINE);
        assertEquals(MobilePresenceTracker.PresenceState.SUSPECT, decision.state());
        assertNull(decision.eventType());
    }

    @Test
    public void testSilenceBecomesOfflineAfterTenMinutes() {
        var decision = MobilePresenceTracker.PresenceDecision.decide(
                MobilePresenceTracker.PresenceState.SUSPECT, true, OFFLINE + 1_000L, SUSPECT, OFFLINE);
        assertEquals(MobilePresenceTracker.PresenceState.OFFLINE, decision.state());
        assertEquals(MobilePresenceTracker.REASON_TIMEOUT_OFFLINE, decision.reason());
        assertEquals(Event.TYPE_MOBILE_PRESENCE_OFFLINE, decision.eventType());
        assertFalse(decision.untrack());
    }

    @Test
    public void testOfflineStaysOfflineSilently() {
        var decision = MobilePresenceTracker.PresenceDecision.decide(
                MobilePresenceTracker.PresenceState.OFFLINE, true, OFFLINE + 3_600_000L, SUSPECT, OFFLINE);
        assertEquals(MobilePresenceTracker.PresenceState.OFFLINE, decision.state());
        assertNull(decision.eventType());
    }

    @Test
    public void testFreshTrafficAfterTimeoutRecovers() {
        var decision = MobilePresenceTracker.PresenceDecision.decide(
                MobilePresenceTracker.PresenceState.OFFLINE, true, 5_000L, SUSPECT, OFFLINE);
        assertEquals(MobilePresenceTracker.PresenceState.ONLINE, decision.state());
        assertEquals(MobilePresenceTracker.REASON_RECONNECT, decision.reason());
        assertEquals(Event.TYPE_MOBILE_PRESENCE_RECOVERED, decision.eventType());
    }

    @Test
    public void testWithoutJourneyOnlyOfflineAndUntrack() {
        var decision = MobilePresenceTracker.PresenceDecision.decide(
                MobilePresenceTracker.PresenceState.OFFLINE, false, OFFLINE + 1L, SUSPECT, OFFLINE);
        assertEquals(MobilePresenceTracker.PresenceState.OFFLINE, decision.state());
        assertNull(decision.eventType());
        assertTrue(decision.untrack());
        // Vista colgada sin jornada también drena por temporizador, sin reabrir.
        var stuck = MobilePresenceTracker.PresenceDecision.decide(
                MobilePresenceTracker.PresenceState.ONLINE, false, OFFLINE + 1L, SUSPECT, OFFLINE);
        assertEquals(MobilePresenceTracker.PresenceState.OFFLINE, stuck.state());
        assertEquals(Event.TYPE_MOBILE_PRESENCE_OFFLINE, stuck.eventType());
        assertTrue(stuck.untrack());
    }

    @Test
    public void testShortBlipNeverLeavesOnline() {
        // Handover / reintento MQTT / buffering de 4 min: ni SUSPECT.
        var decision = MobilePresenceTracker.PresenceDecision.decide(
                MobilePresenceTracker.PresenceState.ONLINE, true, 240_000L, SUSPECT, OFFLINE);
        assertEquals(MobilePresenceTracker.PresenceState.ONLINE, decision.state());
        assertNull(decision.eventType());
    }
}
