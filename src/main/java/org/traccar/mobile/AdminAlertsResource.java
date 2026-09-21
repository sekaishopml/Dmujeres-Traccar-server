package org.traccar.mobile;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.traccar.api.BaseResource;

/**
 * Alertas operativas del panel web: GET /api/admin/alerts.
 *
 * <p>Autenticación estándar de Traccar (sesión del panel) + administrador,
 * igual que el resto de endpoints internos. La respuesta combina la lista de
 * alertas (severidad crítica/advertencia/información, con deviceName y mensaje
 * en español) y el estado del sistema (respaldo, disco, arranque del server y
 * watchdog). Toda la agregación vive en {@link AdminAlertsService}; aquí solo
 * está la capa HTTP.</p>
 */
@Path("admin/alerts")
@Produces(MediaType.APPLICATION_JSON)
public class AdminAlertsResource extends BaseResource {

    private final AdminAlertsService alerts;

    @Inject
    public AdminAlertsResource(AdminAlertsService alerts) {
        this.alerts = alerts;
    }

    @GET
    public AdminAlertsService.AlertsReport get() throws Exception {
        permissionsService.checkAdmin(getUserId());
        return alerts.collect();
    }
}
