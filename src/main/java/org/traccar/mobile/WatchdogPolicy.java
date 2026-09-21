package org.traccar.mobile;

/**
 * Política pura (JVM) del watchdog server-side de silencio/stalled (F0).
 *
 * NO usa valores arbitrarios: deriva el umbral del modo de captura conocido
 * de la app (mirroring de las políticas client-side ActivePollPolicy/
 * AdaptiveDistancePolicy):
 * - MOVING  : captura activa cada 5 s (MOVING_INTERVAL_SECONDS), pero con
 *             pantalla apagada el duty-cycle retrocede a la base (120 s).
 *             R7: umbral 240 s (= 2× la base) — el FCM que despierta es barato
 *             y ya está rate-limited (5/h), así que se prefiere despertar
 *             antes que regalar ruta. Con apps viejas (<1.1.8) el efecto es
 *             el mismo: el probe las despierta y reportan.
 * - STATIONARY: la base real desde 1.1.8 es 120 s (keeper 2 min con jornada).
 *             R7: umbral 360 s (= 3× la base) — antes eran 900 s porque las
 *             versiones viejas reportaban cada 15 min; ya no aplica.
 *
 * La regla es explicable: threshold = max(esperado*factor, piso). No se usa
 * para declarar "proceso muerto": el watchdog solo CLASIFICA silencios y
 * deja la causa en el evento (SILENT es estado, no sentencia).
 */
public final class WatchdogPolicy {

    /** App: intervalo activo en marcha (AdaptiveDistancePolicy.MOVING_INTERVAL_SECONDS). */
    public static final long ACTIVE_INTERVAL_S = 5L;
    /** App: base de captura en quietud (duty-cycle de la app). */
    public static final long STATIONARY_INTERVAL_S = 120L;

    public static final long MOVING_THRESHOLD_MS = 240_000L;   // 4 min (R7)
    public static final long STATIONARY_THRESHOLD_MS = 360_000L; // 6 min (R7)

    private WatchdogPolicy() {
    }

    /**
     * Umbral de "sin coordenadas nuevas" según el modo del dispositivo.
     * @param moving true si el device está en marcha (motion MOVING / speed
     *               real reciente en la última posición conocida).
     */
    public static long stalledThresholdMs(boolean moving) {
        return moving ? MOVING_THRESHOLD_MS : STATIONARY_THRESHOLD_MS;
    }

    /** Explicación auditable del umbral (se guarda en el evento). */
    public static String explain(boolean moving, long silenceMs) {
        return (moving
                ? "modo=moving base=120s×2 umbral=240s"
                : "modo=stationary base=120s×3 umbral=360s")
            + " silencio=" + (silenceMs / 1000L) + "s";
    }
}
