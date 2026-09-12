/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.traccar.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.database.NotificationManager;
import org.traccar.model.Device;
import org.traccar.model.Event;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Máquina de presencia del canal móvil: la única fuente de verdad sobre si un
 * colaborador con jornada activa está ONLINE, SUSPECT u OFFLINE.
 *
 * <p>Elimina los falsos OFFLINE separando 5 dimensiones que antes se mezclaban:
 * <ul>
 *   <li>PRESENCE (aquí): ONLINE si hay telemetría válida hace menos de
 *   {@code suspectSeconds} (def. 300 s), SUSPECT hasta {@code offlineSeconds}
 *   (def. 600 s), OFFLINE más allá o al cerrar la jornada.</li>
 *   <li>GPS: OK (fixes fluyendo) / NO_FIX (la app reporta gps=off) / STALE (sin
 *   fixes hace más de {@code gpsStaleSeconds} aunque la presencia fluya).</li>
 *   <li>NETWORK: WIFI / MOBILE / NONE / UNVALIDATED (transporte + VALIDATED del
 *   payload; WiFi sin validar NO es Internet).</li>
 *   <li>MQTT: CONNECTED / CONNECTING / DISCONNECTED. Solo lo mueve el tráfico
 *   MQTT (un batch HTTP no prueba que la sesión MQTT viva) y el LWT.</li>
 *   <li>OUTBOX: EMPTY / PENDING / DRAINING, del gauge {@code pending} que la app
 *   ya reporta (baja ⇒ drenando).</li>
 * </ul>
 *
 * <p>Reglas que matan los falsos OFFLINE:
 * <ul>
 *   <li>LWT = "sesión MQTT perdida inesperadamente", NO "teléfono apagado":
 *   con jornada activa pasa a SUSPECT (nunca directo a OFFLINE); sin jornada se ignora.</li>
 *   <li>Ni la falta de fix GPS, ni un MQTT caído temporalmente, ni el buffering
 *   offline, ni un handover marcan OFFLINE: solo el temporizador (10 min sin nada)
 *   o el cierre explícito de jornada.</li>
 *   <li>El replay offline (fixTime de hace horas) se acepta e ingresa igual;
 *   mueve lastSeen por llegada (el transporte está vivo) pero NUNCA reabre una
 *   jornada ya cerrada (stale journeyId se ignora con log + métrica).</li>
 *   <li>fixTime (hora GPS real) y serverReceivedAt (llegada) viajan separados
 *   (ver {@link MobileIngestionService#toPosition}); aquí solo se usa llegada
 *   para liveness y fixTime para la dimensión GPS.</li>
 * </ul>
 *
 * <p>Persistencia sin migración de esquema: el estado vive en memoria y solo se
 * escribe a {@code tc_devices.attributes} (mismo patrón que
 * {@code mobile.degraded}) EN TRANSICIÓN, nunca por mensaje. Tras reiniciar se
 * rehidrata desde atributos + {@code lastUpdate}. Las dimensiones (gps/net/mqtt/
 * outbox) viajan en el UPDATE que {@link MobileTelemetryApplier} ya hace por
 * mensaje: cero escrituras extra.
 */
@Singleton
public class MobilePresenceTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(MobilePresenceTracker.class);

    public enum PresenceState { UNKNOWN, ONLINE, SUSPECT, OFFLINE }

    public enum GpsState { UNKNOWN, OK, NO_FIX, STALE }

    public enum NetState { UNKNOWN, WIFI, MOBILE, NONE, UNVALIDATED }

    public enum MqttState { UNKNOWN, CONNECTED, CONNECTING, DISCONNECTED }

    public enum OutboxState { UNKNOWN, EMPTY, PENDING, DRAINING }

    public static final String REASON_JOURNEY_START = "JOURNEY_START";
    public static final String REASON_JOURNEY_ENDED = "JOURNEY_ENDED";
    public static final String REASON_RECONNECT = "RECONNECT";
    public static final String REASON_SESSION_LOST = "SESSION_LOST";
    public static final String REASON_TIMEOUT_SUSPECT = "TIMEOUT_SUSPECT";
    public static final String REASON_TIMEOUT_OFFLINE = "TIMEOUT_OFFLINE";
    public static final String REASON_BOOT = "BOOT";

    public static final String ATTR_STATE = "mobile.presenceState";
    public static final String ATTR_AT = "mobile.presenceAt";
    public static final String ATTR_REASON = "mobile.presenceReason";
    public static final String ATTR_GPS = "mobile.gpsState";
    public static final String ATTR_NET = "mobile.netState";
    public static final String ATTR_MQTT = "mobile.mqttState";
    public static final String ATTR_OUTBOX = "mobile.outboxState";
    public static final String ATTR_LAST_ENDED = "mobile.lastEndedJourneyId";

    /** Vista en memoria por dispositivo (campos privados por checkstyle; ver accesores). */
    public static class PresenceView {
        private PresenceState state = PresenceState.UNKNOWN;
        private String reason = REASON_BOOT;
        private long lastSeenAt;
        private long lastTransitionAt;
        private long lastPositionArrivalAt;
        private long lastFixTimeMs;
        private GpsState gps = GpsState.UNKNOWN;
        private NetState network = NetState.UNKNOWN;
        private MqttState mqtt = MqttState.UNKNOWN;
        private OutboxState outbox = OutboxState.UNKNOWN;
        private long prevPending;
        private boolean gpsReportedOff;
        private long lastEndedJourneyId;

        public PresenceState getState() {
            return state;
        }

        public void setState(PresenceState state) {
            this.state = state;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public long getLastSeenAt() {
            return lastSeenAt;
        }

        public void setLastSeenAt(long lastSeenAt) {
            this.lastSeenAt = lastSeenAt;
        }

        public long getLastTransitionAt() {
            return lastTransitionAt;
        }

        public void setLastTransitionAt(long lastTransitionAt) {
            this.lastTransitionAt = lastTransitionAt;
        }

        public long getLastPositionArrivalAt() {
            return lastPositionArrivalAt;
        }

        public void setLastPositionArrivalAt(long lastPositionArrivalAt) {
            this.lastPositionArrivalAt = lastPositionArrivalAt;
        }

        public long getLastFixTimeMs() {
            return lastFixTimeMs;
        }

        public void setLastFixTimeMs(long lastFixTimeMs) {
            this.lastFixTimeMs = lastFixTimeMs;
        }

        public GpsState getGps() {
            return gps;
        }

        public void setGps(GpsState gps) {
            this.gps = gps;
        }

        public NetState getNetwork() {
            return network;
        }

        public void setNetwork(NetState network) {
            this.network = network;
        }

        public MqttState getMqtt() {
            return mqtt;
        }

        public void setMqtt(MqttState mqtt) {
            this.mqtt = mqtt;
        }

        public OutboxState getOutbox() {
            return outbox;
        }

        public void setOutbox(OutboxState outbox) {
            this.outbox = outbox;
        }

        public long getPrevPending() {
            return prevPending;
        }

        public void setPrevPending(long prevPending) {
            this.prevPending = prevPending;
        }

        public boolean isGpsReportedOff() {
            return gpsReportedOff;
        }

        public void setGpsReportedOff(boolean gpsReportedOff) {
            this.gpsReportedOff = gpsReportedOff;
        }

        public long getLastEndedJourneyId() {
            return lastEndedJourneyId;
        }

        public void setLastEndedJourneyId(long lastEndedJourneyId) {
            this.lastEndedJourneyId = lastEndedJourneyId;
        }
    }

    public record Transition(boolean changed, PresenceState from, PresenceState to, String reason) {
        public static Transition none(PresenceState state, String reason) {
            return new Transition(false, state, state, reason);
        }
    }

    /**
     * Decisión pura del temporizador (testeable sin DI): dado el estado actual,
     * si la jornada sigue activa y la edad del último dato, decide el estado.
     * El tráfico fresco transiciona en onArrival/onStarted (no aquí).
     */
    public static final class PresenceDecision {

        public record Decision(PresenceState state, String reason, String eventType, boolean untrack) {
        }

        public static Decision decide(
                PresenceState current, boolean journeyActive, long ageMs, long suspectMs, long offlineMs) {
            if (!journeyActive) {
                if (current == PresenceState.OFFLINE) {
                    return new Decision(PresenceState.OFFLINE, REASON_JOURNEY_ENDED, null, true);
                }
                // Jornada cerrada pero vista colgada en ONLINE/SUSPECT (p.ej. replay
                // de started viejo): drena por temporizador, nunca reabre.
                if (ageMs <= suspectMs) {
                    return current == PresenceState.ONLINE
                            ? new Decision(current, null, null, false)
                            : new Decision(PresenceState.ONLINE, REASON_RECONNECT,
                                    Event.TYPE_MOBILE_PRESENCE_RECOVERED, false);
                }
                if (ageMs <= offlineMs) {
                    return current == PresenceState.SUSPECT
                            ? new Decision(current, null, null, false)
                            : new Decision(PresenceState.SUSPECT, REASON_TIMEOUT_SUSPECT,
                                    Event.TYPE_MOBILE_PRESENCE_SUSPECT, false);
                }
                return new Decision(PresenceState.OFFLINE, REASON_TIMEOUT_OFFLINE,
                        current == PresenceState.OFFLINE ? null : Event.TYPE_MOBILE_PRESENCE_OFFLINE, true);
            }
            if (ageMs <= suspectMs) {
                if (current == PresenceState.ONLINE) {
                    return new Decision(current, null, null, false);
                }
                return new Decision(PresenceState.ONLINE, REASON_RECONNECT,
                        Event.TYPE_MOBILE_PRESENCE_RECOVERED, false);
            }
            if (ageMs <= offlineMs) {
                if (current == PresenceState.SUSPECT) {
                    return new Decision(current, null, null, false);
                }
                return new Decision(PresenceState.SUSPECT, REASON_TIMEOUT_SUSPECT,
                        Event.TYPE_MOBILE_PRESENCE_SUSPECT, false);
            }
            if (current == PresenceState.OFFLINE) {
                return new Decision(current, null, null, false);
            }
            return new Decision(PresenceState.OFFLINE, REASON_TIMEOUT_OFFLINE,
                    Event.TYPE_MOBILE_PRESENCE_OFFLINE, false);
        }
    }

    private final long heartbeatMs;
    private final long suspectMs;
    private final long offlineMs;
    private final long gpsStaleMs;
    private final long mqttGraceMs;
    private final Storage storage;
    private final NotificationManager notificationManager;
    private final MobileJourneyRegistry journeyRegistry;

    private final ConcurrentHashMap<Long, PresenceView> views = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> lastStaleWarnAt = new ConcurrentHashMap<>();

    private final AtomicLong transitions = new AtomicLong();
    private final AtomicLong suspectEvents = new AtomicLong();
    private final AtomicLong offlineEvents = new AtomicLong();
    private final AtomicLong recoveredEvents = new AtomicLong();
    private final AtomicLong lwtEvents = new AtomicLong();
    private final AtomicLong staleJourneyBlocks = new AtomicLong();
    private final AtomicLong endedTotal = new AtomicLong();

    @Inject
    public MobilePresenceTracker(
            Config config, Storage storage, NotificationManager notificationManager,
            MobileJourneyRegistry journeyRegistry) {
        this(
                Math.max(1, config.getInteger(Keys.MOBILE_PRESENCE_HEARTBEAT_SECONDS)) * 1000L,
                Math.max(1, config.getInteger(Keys.MOBILE_PRESENCE_SUSPECT_SECONDS)) * 1000L,
                Math.max(1, config.getInteger(Keys.MOBILE_PRESENCE_OFFLINE_SECONDS)) * 1000L,
                Math.max(1, config.getInteger(Keys.MOBILE_PRESENCE_GPS_STALE_SECONDS)) * 1000L,
                Math.max(1, config.getInteger(Keys.MOBILE_PRESENCE_MQTT_GRACE_SECONDS)) * 1000L,
                storage, notificationManager, journeyRegistry);
    }

    /** Constructor con umbrales explícitos (tests). offlineMs se acota a >= suspectMs. */
    MobilePresenceTracker(
            long heartbeatMs, long suspectMs, long offlineMs, long gpsStaleMs, long mqttGraceMs,
            Storage storage, NotificationManager notificationManager, MobileJourneyRegistry journeyRegistry) {
        this.heartbeatMs = heartbeatMs;
        this.suspectMs = suspectMs;
        this.offlineMs = Math.max(offlineMs, suspectMs);
        this.gpsStaleMs = gpsStaleMs;
        this.mqttGraceMs = mqttGraceMs;
        this.storage = storage;
        this.notificationManager = notificationManager;
        this.journeyRegistry = journeyRegistry;
    }

    /**
     * Llegada de telemetría válida (posición o presencia, cualquier transporte).
     * Mueve lastSeen por LLEGADA (fixTime antiguo de replay también: el transporte
     * está vivo) y refresca las dimensiones GPS/NETWORK/MQTT/OUTBOX en el mapa de
     * atributos (las persiste el UPDATE que ya hace el applier: cero writes extra).
     *
     * @param fixTimeMs hora GPS del fix para posiciones, -1 para presence.
     */
    public void onArrival(Device device, JsonNode root, MobileChannel channel, long fixTimeMs) {
        long now = System.currentTimeMillis();
        PresenceView view = getOrHydrate(device);
        view.setLastSeenAt(now);
        if (fixTimeMs > 0) {
            view.setLastPositionArrivalAt(now);
            view.setLastFixTimeMs(Math.max(view.getLastFixTimeMs(), fixTimeMs));
            view.setGps(GpsState.OK);
            device.getAttributes().put("mobile.lastFixTime", view.getLastFixTimeMs());
        }
        JsonNode payload = root != null ? root.path("payload") : null;
        if (payload != null && !payload.isMissingNode()) {
            if (payload.hasNonNull("gps")) {
                String gps = payload.get("gps").asText();
                view.setGpsReportedOff("off".equals(gps));
                if (view.isGpsReportedOff()) {
                    view.setGps(GpsState.NO_FIX);
                }
            }
            if (payload.hasNonNull("network")) {
                String network = payload.get("network").asText();
                boolean validated = !payload.hasNonNull("validated") || payload.get("validated").asBoolean(true);
                view.setNetwork(mapNetwork(network, validated));
                device.getAttributes().put("mobile.netState", view.getNetwork().name().toLowerCase());
            }
            if (payload.hasNonNull("pending")) {
                long pending = payload.get("pending").asLong(0);
                view.setOutbox(pending <= 0 ? OutboxState.EMPTY
                        : (pending < view.getPrevPending() ? OutboxState.DRAINING : OutboxState.PENDING));
                view.setPrevPending(pending);
                device.getAttributes().put("mobile.outboxState", view.getOutbox().name().toLowerCase());
            }
        }
        if (channel == MobileChannel.MQTT) {
            view.setMqtt(view.getMqtt() == MqttState.DISCONNECTED ? MqttState.CONNECTING : MqttState.CONNECTED);
            device.getAttributes().put("mobile.mqttState", view.getMqtt().name().toLowerCase());
        }
        device.getAttributes().put("mobile.gpsState", view.getGps().name().toLowerCase());
        if (view.getState() != PresenceState.ONLINE && journeyRegistry.isActive(device.getId())) {
            // Recuperación real (SUSPECT/OFFLINE → ONLINE) con evento; el primer
            // contacto (UNKNOWN) transiciona en silencio (el started/heartbeat
            // que sigue pone el motivo; no hay "recuperación" que anunciar).
            String event = view.getState() == PresenceState.SUSPECT || view.getState() == PresenceState.OFFLINE
                    ? Event.TYPE_MOBILE_PRESENCE_RECOVERED : null;
            applyTransition(device, view, PresenceState.ONLINE, REASON_RECONNECT, event, now);
        } else if (view.getState() == PresenceState.UNKNOWN) {
            // Primer contacto sin jornada (p.ej. replay tras el cierre): ONLINE en
            // memoria y persistido, sin evento; el temporizador lo degrada solo.
            applyTransition(device, view, PresenceState.ONLINE, REASON_RECONNECT, null, now);
        }
    }

    /**
     * Resultado del procesamiento: con ACK (accepted/duplicate) la sesión MQTT
     * queda confirmada. Sin confirmación (pending) se queda en CONNECTING.
     */
    public void onSettled(long deviceId, boolean accepted, MobileChannel channel) {
        if (!accepted || channel != MobileChannel.MQTT) {
            return;
        }
        PresenceView view = views.get(deviceId);
        if (view != null && view.getMqtt() == MqttState.CONNECTING) {
            view.setMqtt(MqttState.CONNECTED);
        }
    }

    /**
     * LWT del broker: sesión MQTT perdida inesperadamente. Con jornada activa
     * pasa a SUSPECT (jamás directo a OFFLINE: un handover WiFi→datos dispara
     * LWT y el teléfono sigue capturando). Sin jornada se ignora la presencia
     * (pero igual se marca la sesión MQTT caída si hay vista).
     *
     * @return true si la jornada está activa.
     */
    public boolean onLwt(Device device) {
        long now = System.currentTimeMillis();
        lwtEvents.incrementAndGet();
        boolean active = journeyRegistry.isActive(device.getId());
        PresenceView view = active ? getOrHydrate(device) : views.get(device.getId());
        if (view != null) {
            view.setMqtt(MqttState.DISCONNECTED);
        }
        if (!active) {
            LOGGER.debug("mobile.presence device={} lwt without journey; mqtt=disconnected",
                    device.getUniqueId());
            return false;
        }
        if (view.getState() == PresenceState.ONLINE) {
            applyTransition(device, view, PresenceState.SUSPECT, REASON_SESSION_LOST,
                    Event.TYPE_MOBILE_PRESENCE_SUSPECT, now);
        } else if (view.getState() == PresenceState.SUSPECT) {
            view.setReason(REASON_SESSION_LOST);
            persistTransition(device, view);
            LOGGER.info("mobile.presence device={} suspect reason={} (lwt, session lost)",
                    device.getUniqueId(), REASON_SESSION_LOST);
        } else {
            // OFFLINE o UNKNOWN con sesión recién caída: hay señal de vida
            // (la sesión existía hasta ahora) → SUSPECT, no OFFLINE.
            applyTransition(device, view, PresenceState.SUSPECT, REASON_SESSION_LOST,
                    view.getState() == PresenceState.OFFLINE ? null : Event.TYPE_MOBILE_PRESENCE_SUSPECT, now);
        }
        return true;
    }

    /**
     * Inicio de jornada. Un started con journeyId <= al último cerrado es replay
     * viejo: se acepta el mensaje (para drenar la cola del móvil) pero NO se
     * reabre la jornada.
     *
     * @return true si el llamador debe registrar el inicio en el registry.
     */
    public boolean onStarted(Device device, long journeyId) {
        long now = System.currentTimeMillis();
        PresenceView view = getOrHydrate(device);
        if (journeyId > 0 && view.getLastEndedJourneyId() > 0 && journeyId <= view.getLastEndedJourneyId()) {
            staleJourneyBlocks.incrementAndGet();
            throttledStaleWarn(device.getId(), device.getUniqueId(), journeyId, view.getLastEndedJourneyId());
            return false;
        }
        if (view.getState() != PresenceState.ONLINE || !REASON_JOURNEY_START.equals(view.getReason())) {
            applyTransition(device, view, PresenceState.ONLINE, REASON_JOURNEY_START, null, now);
        }
        return true;
    }

    /** ¿Debe una posición con este journeyId reabrir/registrar jornada? (anti-resurrección). */
    public boolean shouldReopen(long deviceId, String uniqueId, long journeyId) {
        if (journeyId <= 0) {
            return true;
        }
        PresenceView view = views.get(deviceId);
        long lastEnded = view != null ? view.getLastEndedJourneyId() : 0L;
        if (lastEnded > 0 && journeyId <= lastEnded) {
            staleJourneyBlocks.incrementAndGet();
            throttledStaleWarn(deviceId, uniqueId, journeyId, lastEnded);
            return false;
        }
        return true;
    }

    private void throttledStaleWarn(long deviceId, String uniqueId, long journeyId, long lastEnded) {
        long now = System.currentTimeMillis();
        Long last = lastStaleWarnAt.get(deviceId);
        if (last == null || now - last > 60_000L) {
            lastStaleWarnAt.put(deviceId, now);
            LOGGER.warn("mobile.presence device={} stale journey replay ignored (journeyId={} <= lastEnded={}); "
                    + "position ingested, journey NOT reopened", uniqueId, journeyId, lastEnded);
        }
    }

    /** Cierre explícito de jornada: OFFLINE inmediato con motivo. */
    public void onEnded(Device device, long journeyId) {
        long now = System.currentTimeMillis();
        endedTotal.incrementAndGet();
        PresenceView view = getOrHydrate(device);
        if (journeyId > 0) {
            view.setLastEndedJourneyId(journeyId);
            device.getAttributes().put(ATTR_LAST_ENDED, journeyId);
        }
        applyTransition(device, view, PresenceState.OFFLINE, REASON_JOURNEY_ENDED, null, now);
    }

    /**
     * Evaluación del temporizador (la llama el monitor cada 30 s por dispositivo
     * con jornada activa o presencia viva). Aplica timeouts, reglas de idle de
     * dimensiones (GPS STALE, MQTT sin tráfico) y libera vistas OFFLINE sin jornada.
     */
    public Transition evaluate(Device device, long now) {
        PresenceView view = getOrHydrate(device);
        boolean active = journeyRegistry.isActive(device.getId());
        PresenceDecision.Decision decision = PresenceDecision.decide(
                view.getState(), active, now - view.getLastSeenAt(), suspectMs, offlineMs);

        boolean dimsChanged = false;
        if (active && view.getState() != PresenceState.OFFLINE) {
            if (view.getMqtt() == MqttState.CONNECTED && now - view.getLastSeenAt() > mqttGraceMs) {
                view.setMqtt(MqttState.DISCONNECTED);
                dimsChanged = true;
                LOGGER.info("mobile.presence device={} mqtt connected->disconnected (idle {}s, no lwt)",
                        device.getUniqueId(), (now - view.getLastSeenAt()) / 1000);
            }
            if (!view.isGpsReportedOff() && view.getGps() != GpsState.STALE) {
                boolean noFixEver = view.getLastPositionArrivalAt() <= 0;
                boolean fixAged = !noFixEver && now - view.getLastPositionArrivalAt() > gpsStaleMs;
                boolean flowWithoutFix = noFixEver && now - view.getLastSeenAt() > gpsStaleMs;
                if (fixAged || flowWithoutFix) {
                    view.setGps(GpsState.STALE);
                    dimsChanged = true;
                }
            }
        }

        if (decision.state() == view.getState() && !dimsChanged) {
            if (decision.untrack()) {
                views.remove(device.getId());
            }
            return Transition.none(view.getState(), view.getReason());
        }
        Transition transition = applyTransition(
                device, view, decision.state(),
                decision.reason() != null ? decision.reason() : view.getReason(), decision.eventType(), now);
        if (decision.untrack()) {
            views.remove(device.getId());
        }
        return transition;
    }

    /** Vistas seguidas en memoria (para el barrido del monitor). */
    public java.util.List<Long> trackedIds() {
        return new java.util.ArrayList<>(views.keySet());
    }

    public PresenceView getView(long deviceId) {
        return views.get(deviceId);
    }

    public long getTransitions() {
        return transitions.get();
    }

    public long getSuspectEvents() {
        return suspectEvents.get();
    }

    public long getOfflineEvents() {
        return offlineEvents.get();
    }

    public long getRecoveredEvents() {
        return recoveredEvents.get();
    }

    public long getLwtEvents() {
        return lwtEvents.get();
    }

    public long getStaleJourneyBlocks() {
        return staleJourneyBlocks.get();
    }

    public long getEndedTotal() {
        return endedTotal.get();
    }

    public String formatStats() {
        long online = 0, suspect = 0, offline = 0, unknown = 0, overdue = 0;
        long now = System.currentTimeMillis();
        for (PresenceView view : views.values()) {
            switch (view.getState()) {
                case ONLINE -> online++;
                case SUSPECT -> suspect++;
                case OFFLINE -> offline++;
                default -> unknown++;
            }
            if (now - view.getLastSeenAt() > heartbeatMs) {
                overdue++;
            }
        }
        return "presence={online=" + online + " suspect=" + suspect + " offline=" + offline
                + " unknown=" + unknown + " overdueHeartbeat=" + overdue + "}"
                + " transitions=" + transitions.get()
                + " suspectEvents=" + suspectEvents.get()
                + " offlineEvents=" + offlineEvents.get()
                + " recoveredEvents=" + recoveredEvents.get()
                + " lwt=" + lwtEvents.get()
                + " staleJourneyBlocked=" + staleJourneyBlocks.get()
                + " ended=" + endedTotal.get();
    }

    private Transition applyTransition(
            Device device, PresenceView view, PresenceState to, String reason, String eventType, long now) {
        PresenceState from = view.getState();
        if (from == to && reason.equals(view.getReason())) {
            return Transition.none(from, reason);
        }
        view.setState(to);
        view.setReason(reason);
        view.setLastTransitionAt(now);
        transitions.incrementAndGet();
        persistTransition(device, view);
        if (eventType != null) {
            emitEvent(device, eventType, from, to, reason, now - view.getLastSeenAt());
            switch (eventType) {
                case Event.TYPE_MOBILE_PRESENCE_SUSPECT -> suspectEvents.incrementAndGet();
                case Event.TYPE_MOBILE_PRESENCE_OFFLINE -> offlineEvents.incrementAndGet();
                case Event.TYPE_MOBILE_PRESENCE_RECOVERED -> recoveredEvents.incrementAndGet();
                default -> {
                }
            }
        }
        LOGGER.info("mobile.presence device={} {}->{} reason={} age={}s",
                device.getUniqueId(), from, to, reason, (now - view.getLastSeenAt()) / 1000);
        return new Transition(true, from, to, reason);
    }

    private void persistTransition(Device device, PresenceView view) {
        try {
            Map<String, Object> attributes = device.getAttributes();
            attributes.put(ATTR_STATE, view.getState().name().toLowerCase());
            attributes.put(ATTR_AT, view.getLastTransitionAt());
            attributes.put(ATTR_REASON, view.getReason());
            attributes.put(ATTR_GPS, view.getGps().name().toLowerCase());
            attributes.put(ATTR_NET, view.getNetwork().name().toLowerCase());
            attributes.put(ATTR_MQTT, view.getMqtt().name().toLowerCase());
            attributes.put(ATTR_OUTBOX, view.getOutbox().name().toLowerCase());
            attributes.put(ATTR_LAST_ENDED, view.getLastEndedJourneyId());
            storage.updateObject(device, new Request(
                    new Columns.Include("attributes"), new Condition.Equals("id", device.getId())));
        } catch (Exception error) {
            LOGGER.warn("mobile.presence failed to persist transition for device {}", device.getId(), error);
        }
    }

    private void emitEvent(
            Device device, String type, PresenceState from, PresenceState to, String reason, long ageMs) {
        try {
            Event event = new Event(type, device.getId());
            event.getAttributes().put("mobileSeverity",
                    Event.TYPE_MOBILE_PRESENCE_RECOVERED.equals(type) ? "info" : "warning");
            event.getAttributes().put("presenceFrom", from.name().toLowerCase());
            event.getAttributes().put("presenceTo", to.name().toLowerCase());
            event.getAttributes().put("reason", reason);
            event.getAttributes().put("silenceMinutes", ageMs / 60_000);
            Object battery = device.getAttributes().get("mobile.battery");
            if (battery != null) {
                event.getAttributes().put("lastBattery", battery);
            }
            Object network = device.getAttributes().get("mobile.network");
            if (network != null) {
                event.getAttributes().put("network", network.toString());
            }
            notificationManager.updateEvents(Collections.singletonMap(event, null));
        } catch (Exception error) {
            LOGGER.warn("mobile.presence failed to emit {} for device {}", type, device.getId(), error);
        }
    }

    private PresenceView getOrHydrate(Device device) {
        return views.computeIfAbsent(device.getId(), id -> hydrate(device));
    }

    /**
     * Rehidrata tras reiniciar el servidor desde atributos + lastUpdate (sin
     * lecturas extra: el monitor ya cargó el device). lastSeen NO se persiste
     * por mensaje (amplificación de writes): se aproxima con lastUpdate, que el
     * core actualiza en cada ONLINE.
     */
    private PresenceView hydrate(Device device) {
        PresenceView view = new PresenceView();
        Map<String, Object> attributes = device.getAttributes();
        view.setState(parseState(str(attributes, ATTR_STATE), PresenceState.UNKNOWN));
        String reason = str(attributes, ATTR_REASON);
        view.setReason(reason == null || reason.isBlank() ? REASON_BOOT : reason);
        view.setLastTransitionAt(num(attributes, ATTR_AT));
        view.setGps(parseState(str(attributes, ATTR_GPS), GpsState.UNKNOWN, GpsState.class));
        view.setNetwork(parseState(str(attributes, ATTR_NET), NetState.UNKNOWN, NetState.class));
        view.setMqtt(parseState(str(attributes, ATTR_MQTT), MqttState.UNKNOWN, MqttState.class));
        view.setOutbox(parseState(str(attributes, ATTR_OUTBOX), OutboxState.UNKNOWN, OutboxState.class));
        view.setLastEndedJourneyId(num(attributes, ATTR_LAST_ENDED));
        view.setPrevPending(num(attributes, "mobile.pending"));
        Object gps = attributes.get("mobile.gps");
        view.setGpsReportedOff(gps != null && "off".equals(gps.toString()));
        if (device.getLastUpdate() != null) {
            view.setLastSeenAt(device.getLastUpdate().getTime());
        } else {
            // Fila legacy sin lastUpdate: beneficio de la duda una ventana; el
            // temporizador transiciona solo cuando el silencio sea real.
            view.setLastSeenAt(System.currentTimeMillis());
        }
        return view;
    }

    private static NetState mapNetwork(String network, boolean validated) {
        if (network == null || network.isBlank()) {
            return NetState.UNKNOWN;
        }
        switch (network) {
            case "none":
                return NetState.NONE;
            case "wifi":
                return validated ? NetState.WIFI : NetState.UNVALIDATED;
            case "mobile":
                return validated ? NetState.MOBILE : NetState.UNVALIDATED;
            default:
                return validated ? NetState.UNKNOWN : NetState.UNVALIDATED;
        }
    }

    private static <T extends Enum<T>> T parseState(String value, T fallback, Class<T> type) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase());
        } catch (IllegalArgumentException error) {
            return fallback;
        }
    }

    private static PresenceState parseState(String value, PresenceState fallback) {
        return parseState(value, fallback, PresenceState.class);
    }

    private static String str(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        return value != null ? value.toString() : null;
    }

    private static long num(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (Exception error) {
            return 0L;
        }
    }
}
