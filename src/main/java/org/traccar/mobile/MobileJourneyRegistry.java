package org.traccar.mobile;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.model.Device;
import org.traccar.storage.Storage;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Request;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registro en memoria de dispositivos con jornada activa (deviceId -> journeyId).
 * Lo mantiene MobileIngestionService al procesar presence/position, y lo consulta
 * MobileSilenceMonitor para no recorrer todos los dispositivos cada ciclo.
 */
@Singleton
public class MobileJourneyRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(MobileJourneyRegistry.class);

    private final ConcurrentHashMap<Long, Long> activeJourneys = new ConcurrentHashMap<>();

    @Inject
    public MobileJourneyRegistry(Storage storage) {
        reload(storage);
    }

    /**
     * Recarga las jornadas activas desde la base (para arranques en caliente).
     * Consulta solo los atributos necesarios; la columna es VARCHAR y el JSON es válido.
     */
    public synchronized void reload(Storage storage) {
        activeJourneys.clear();
        try {
            List<Device> devices = storage.getObjects(Device.class, new Request(
                    new Columns.Include("id", "attributes")));
            for (Device device : devices) {
                Object journeyIdObj = device.getAttributes().get("mobile.journeyId");
                if (journeyIdObj == null) continue;
                long journeyId = 0L;
                if (journeyIdObj instanceof Number) {
                    journeyId = ((Number) journeyIdObj).longValue();
                } else {
                    try { journeyId = Long.parseLong(journeyIdObj.toString()); } catch (Exception ignored) {}
                }
                if (journeyId > 0L) {
                    activeJourneys.put(device.getId(), journeyId);
                }
            }
            LOGGER.info("Mobile journey registry loaded {} active journeys", activeJourneys.size());
        } catch (Exception error) {
            LOGGER.warn("Failed to load active journeys", error);
        }
    }

    public void start(long deviceId, long journeyId) {
        if (journeyId > 0L) {
            activeJourneys.put(deviceId, journeyId);
        }
    }

    public void end(long deviceId) {
        activeJourneys.remove(deviceId);
    }

    public boolean isActive(long deviceId) {
        return activeJourneys.containsKey(deviceId);
    }

    public List<Long> activeDeviceIds() {
        return new ArrayList<>(activeJourneys.keySet());
    }
}