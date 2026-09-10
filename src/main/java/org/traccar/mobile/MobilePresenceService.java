package org.traccar.mobile;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.database.NotificationManager;
import org.traccar.model.Device;
import org.traccar.model.MobileMessage;
import org.traccar.session.ConnectionManager;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.Date;

/**
 * Finaliza los mensajes de presencia del canal móvil (heartbeat o señal de
 * inicio/fin de jornada): cambia el estado en tiempo real y actualiza la
 * telemetría SIN persistir posiciones ficticias.
 * Extraído de {@link MobileIngestionService} sin cambios de lógica.
 */
@Singleton
public class MobilePresenceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MobilePresenceService.class);

    public enum PresenceOutcome { ACCEPTED, PENDING }

    private final MobileMessageStore messages;
    private final MobileJourneyRegistry journeyRegistry;
    private final ConnectionManager connectionManager;
    private final NotificationManager notificationManager;
    private final MobileTelemetryApplier telemetry;
    private final Storage storage;

    @Inject
    public MobilePresenceService(
            MobileMessageStore messages,
            MobileJourneyRegistry journeyRegistry,
            ConnectionManager connectionManager,
            NotificationManager notificationManager,
            MobileTelemetryApplier telemetry,
            Storage storage) {
        this.messages = messages;
        this.journeyRegistry = journeyRegistry;
        this.connectionManager = connectionManager;
        this.notificationManager = notificationManager;
        this.telemetry = telemetry;
        this.storage = storage;
    }

    public PresenceOutcome handlePresence(
            Device device, MobileEnvelope envelope, JsonNode root, MobileMessage message) {
        try {
            messages.completeWithoutPosition(message);
            MobileTelemetryMonitor.TelemetrySnapshot before =
                    MobileTelemetryMonitor.capture(device);
            telemetry.applyTelemetry(device, root);
            JsonNode presence = root.path("payload");
            boolean started = presence.hasNonNull("journeyStarted")
                    && presence.get("journeyStarted").asBoolean();
            boolean ended = presence.hasNonNull("journeyEnded")
                    && presence.get("journeyEnded").asBoolean();
            if (ended) {
                journeyRegistry.end(device.getId());
                device.getAttributes().put("mobile.journeyId", 0L);
                device.getAttributes().put("mobile.journeyEndedAt", System.currentTimeMillis());
                // Persistir el fin de jornada en BD: ConnectionManager.updateDevice solo
                // guarda status/lastUpdate, así que sin esta escritura quedaría una
                // jornada fantasma (journeyId>0 en tc_devices) tras reiniciar el servidor.
                storage.updateObject(device, new Request(
                        new Columns.Include("attributes"), new Condition.Equals("id", device.getId())));
            } else if (started) {
                // El journeyId de inicio ya se persiste vía applyTelemetry (mobile.journeyId
                // en payload → attributes + storage.updateObject con Columns.Include("attributes")).
                long journeyId = presence.hasNonNull("journeyId")
                        ? presence.get("journeyId").asLong() : 0L;
                journeyRegistry.start(device.getId(), journeyId);
            }
            connectionManager.updateDevice(device.getId(),
                    ended ? Device.STATUS_OFFLINE : Device.STATUS_ONLINE, new Date());
            connectionManager.updateDevice(true, device);
            // Detectar cambios de telemetría y crear eventos (GPS off, red, batería).
            try {
                var events = MobileTelemetryMonitor.detectChanges(device, before, root);
                for (var event : events) {
                    notificationManager.updateEvents(
                            java.util.Collections.singletonMap(event, null));
                }
            } catch (Exception eventsError) {
                LOGGER.warn("Failed to detect telemetry events", eventsError);
            }
            return PresenceOutcome.ACCEPTED;
        } catch (Exception error) {
            LOGGER.warn("Failed to finalize presence heartbeat", error);
            return PresenceOutcome.PENDING;
        }
    }
}
