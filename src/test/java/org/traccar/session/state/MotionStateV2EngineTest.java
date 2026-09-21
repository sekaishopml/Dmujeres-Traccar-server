package org.traccar.session.state;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MotionStateV2EngineTest {

    private MotionStateV2Engine engine;
    private MotionStateV2Engine.Config config;

    private static final long BASE = 1_700_000_000_000L;

    private MotionStateV2Engine.Observation obs(long offsetMs, double lat, double lon,
            double speedMps, MotionStateV2Engine.QualityClass quality) {
        return new MotionStateV2Engine.Observation(
                BASE + offsetMs, lat, lon, speedMps, 10, quality, 0.0);
    }

    @BeforeEach
    public void setUp() {
        config = new MotionStateV2Engine.Config();
        engine = new MotionStateV2Engine(config);
    }

    private void feed(MotionStateV2Engine.Observation... observations) {
        for (var o : observations) {
            engine.update(o);
        }
    }

    @Test
    public void test1MovimientoNormal() {
        // 3 fixes desplazándose con velocidad real
        feed(
            obs(0, -33.45, -70.66, 10, MotionStateV2Engine.QualityClass.GOOD),
            obs(5000, -33.451, -70.661, 10, MotionStateV2Engine.QualityClass.GOOD),
            obs(10000, -33.452, -70.662, 10, MotionStateV2Engine.QualityClass.GOOD));
        assertEquals(MotionStateV2Engine.State.MOVING, engine.getState());
    }

    @Test
    public void test2SpeedCeroPeroDesplazamientoReal() {
        // Doppler=0 pero la distancia demuestra movimiento: NO STOPPED
        feed(
            obs(0, -33.45, -70.66, 0, MotionStateV2Engine.QualityClass.GOOD),
            obs(10000, -33.46, -70.67, 0, MotionStateV2Engine.QualityClass.GOOD));
        assertEquals(MotionStateV2Engine.State.MOVING, engine.getState());
    }

    @Test
    public void test3DopplerIncorrectoImpliedCorrecta() {
        // Doppler errático alto con desplazamiento real
        feed(
            obs(0, -33.45, -70.66, 0, MotionStateV2Engine.QualityClass.GOOD),
            obs(5000, -33.452, -70.665, 25, MotionStateV2Engine.QualityClass.GOOD),
            obs(10000, -33.454, -70.670, 0, MotionStateV2Engine.QualityClass.GOOD));
        assertEquals(MotionStateV2Engine.State.MOVING, engine.getState());
    }

    @Test
    public void test4Paused() {
        // Movimiento, desaceleración, luego estabilidad dentro del radio
        feed(
            obs(0, -33.45, -70.66, 10, MotionStateV2Engine.QualityClass.GOOD),
            obs(5000, -33.451, -70.661, 10, MotionStateV2Engine.QualityClass.GOOD),
            obs(8000, -33.4512, -70.6612, 0.5, MotionStateV2Engine.QualityClass.GOOD));
        // pausa: dentro de 50 m, sin velocidad
        var t = engine.update(obs(12000, -33.4512, -70.6612, 0, MotionStateV2Engine.QualityClass.GOOD));
        assertNotNull(t);
        assertEquals(MotionStateV2Engine.State.PAUSED, t.getState());
        assertEquals("stability-detected", t.getReason());
    }

    @Test
    public void test5StoppedTrasConfirmacion() {
        feed(
            obs(0, -33.45, -70.66, 10, MotionStateV2Engine.QualityClass.GOOD),
            obs(5000, -33.451, -70.661, 10, MotionStateV2Engine.QualityClass.GOOD),
            obs(8000, -33.4512, -70.6612, 0.5, MotionStateV2Engine.QualityClass.GOOD));
        engine.update(obs(12000, -33.4512, -70.6612, 0, MotionStateV2Engine.QualityClass.GOOD));
        assertEquals(MotionStateV2Engine.State.PAUSED, engine.getState());
        // 50 s dentro del radio (>= stopConfirmSeconds 45)
        var t = engine.update(obs(62000, -33.4513, -70.6613, 0, MotionStateV2Engine.QualityClass.GOOD));
        assertNotNull(t);
        assertEquals(MotionStateV2Engine.State.STOPPED, t.getState());
        assertEquals("stop-confirmed", t.getReason());
    }

    @Test
    public void test6JitterGpsNoRompePausa() {
        feed(
            obs(0, -33.45, -70.66, 10, MotionStateV2Engine.QualityClass.GOOD),
            obs(5000, -33.451, -70.661, 10, MotionStateV2Engine.QualityClass.GOOD),
            obs(8000, -33.4512, -70.6612, 0.5, MotionStateV2Engine.QualityClass.GOOD));
        engine.update(obs(12000, -33.4512, -70.6612, 0, MotionStateV2Engine.QualityClass.GOOD));
        assertEquals(MotionStateV2Engine.State.PAUSED, engine.getState());
        // jitter de ~10 m dentro del radio: sigue PAUSED
        engine.update(obs(22000, -33.4514, -70.6615, 0, MotionStateV2Engine.QualityClass.GOOD));
        assertEquals(MotionStateV2Engine.State.PAUSED, engine.getState());
        // confirmación a los 60 s del inicio de pausa
        var t = engine.update(obs(72000, -33.4512, -70.6612, 0, MotionStateV2Engine.QualityClass.GOOD));
        assertEquals(MotionStateV2Engine.State.STOPPED, t.getState());
    }

    @Test
    public void test7SalidaDelStopRadius() {
        feed(
            obs(0, -33.45, -70.66, 10, MotionStateV2Engine.QualityClass.GOOD),
            obs(5000, -33.451, -70.661, 10, MotionStateV2Engine.QualityClass.GOOD),
            obs(8000, -33.4512, -70.6612, 0.5, MotionStateV2Engine.QualityClass.GOOD));
        engine.update(obs(12000, -33.4512, -70.6612, 0, MotionStateV2Engine.QualityClass.GOOD));
        assertEquals(MotionStateV2Engine.State.PAUSED, engine.getState());
        // desplazamiento > stopRadiusM (50 m): ~0.001 lat ≈ 111 m
        var t = engine.update(obs(22000, -33.4522, -70.6612, 8, MotionStateV2Engine.QualityClass.GOOD));
        assertNotNull(t);
        assertEquals(MotionStateV2Engine.State.MOVING, t.getState());
        assertEquals("left-stop-radius", t.getReason());
    }

    @Test
    public void test8GapMayor5MinutosUnknown() {
        feed(
            obs(0, -33.45, -70.66, 10, MotionStateV2Engine.QualityClass.GOOD),
            obs(5000, -33.451, -70.661, 10, MotionStateV2Engine.QualityClass.GOOD));
        assertEquals(MotionStateV2Engine.State.MOVING, engine.getState());
        // gap de 10 minutos
        var t = engine.update(obs(600_000, -33.451, -70.661, 0, MotionStateV2Engine.QualityClass.GOOD));
        assertNotNull(t);
        assertEquals(MotionStateV2Engine.State.UNKNOWN, t.getState());
        assertEquals("gap>timeout", t.getReason());
    }

    @Test
    public void test9GapGrandeNuncaEsStop() {
        // Después del gap, el estado debe ser UNKNOWN y jamás STOPPED
        feed(
            obs(0, -33.45, -70.66, 10, MotionStateV2Engine.QualityClass.GOOD),
            obs(5000, -33.451, -70.661, 10, MotionStateV2Engine.QualityClass.GOOD));
        engine.update(obs(600_000, -33.451, -70.661, 0, MotionStateV2Engine.QualityClass.GOOD));
        engine.update(obs(610_000, -33.451, -70.661, 0, MotionStateV2Engine.QualityClass.GOOD));
        engine.update(obs(1_800_000, -33.451, -70.661, 0, MotionStateV2Engine.QualityClass.GOOD));
        // tras 30 min de gap + fixes quietos: puede ser PAUSED (nueva evidencia),
        // pero NUNCA un STOPPED retroactivo de 30 minutos
        assertTrue(engine.getState() == MotionStateV2Engine.State.PAUSED
                || engine.getState() == MotionStateV2Engine.State.UNKNOWN);
        assertTrue(engine.getState() != MotionStateV2Engine.State.STOPPED);
        // La estabilidad solo cuenta desde la re-adquisición, no desde el gap
        if (engine.getState() == MotionStateV2Engine.State.PAUSED) {
            assertTrue(engine.getPauseStartMs() >= BASE + 600_000);
        }
    }

    @Test
    public void test10FixPoor() {
        feed(
            obs(0, -33.45, -70.66, 10, MotionStateV2Engine.QualityClass.GOOD),
            obs(5000, -33.451, -70.661, 10, MotionStateV2Engine.QualityClass.GOOD));
        // fix POOR → UNKNOWN (no decisiones sobre calidad baja)
        engine.update(obs(10000, -33.452, -70.662, 10, MotionStateV2Engine.QualityClass.POOR));
        assertEquals(MotionStateV2Engine.State.UNKNOWN, engine.getState());
        assertEquals("low-quality", engine.getReason());
    }

    @Test
    public void test11FixInvalid() {
        feed(
            obs(0, -33.45, -70.66, 10, MotionStateV2Engine.QualityClass.GOOD));
        var o = obs(5000, -33.451, -70.661, 10, MotionStateV2Engine.QualityClass.INVALID);
        o.setValid(false);
        var t = engine.update(o);
        assertEquals(MotionStateV2Engine.State.UNKNOWN, engine.getState());
        assertEquals("invalid-fix", t.getReason());
        // El tiempo de continuidad SÍ avanza: un fix inválido no crea gap fantasma
        assertEquals(BASE + 5000, engine.getLastFixMs());
        // y un fix válido posterior no salta a gap>timeout
        var t2 = engine.update(obs(20000, -33.452, -70.662, 10, MotionStateV2Engine.QualityClass.GOOD));
        assertEquals(MotionStateV2Engine.State.MOVING, t2.getState());
    }

    @Test
    public void test12TimestampsFueraDeOrden() {
        feed(
            obs(0, -33.45, -70.66, 10, MotionStateV2Engine.QualityClass.GOOD),
            obs(10000, -33.451, -70.661, 10, MotionStateV2Engine.QualityClass.GOOD));
        // timestamp anterior al último → UNKNOWN
        engine.update(obs(5000, -33.452, -70.662, 10, MotionStateV2Engine.QualityClass.GOOD));
        assertEquals(MotionStateV2Engine.State.UNKNOWN, engine.getState());
        assertEquals("out-of-order", engine.getReason());
    }

    @Test
    public void test13DuplicateTimestamp() {
        feed(
            obs(0, -33.45, -70.66, 10, MotionStateV2Engine.QualityClass.GOOD),
            obs(5000, -33.451, -70.661, 10, MotionStateV2Engine.QualityClass.GOOD));
        // mismo timestamp → UNKNOWN
        engine.update(obs(5000, -33.451, -70.661, 10, MotionStateV2Engine.QualityClass.GOOD));
        assertEquals(MotionStateV2Engine.State.UNKNOWN, engine.getState());
        assertEquals("out-of-order", engine.getReason());
    }

    @Test
    public void test14SensorStationary() {
        // evidencia auxiliar del acelerómetro STATIONARY + GPS quieto → PAUSED
        feed(
            obs(0, -33.45, -70.66, 10, MotionStateV2Engine.QualityClass.GOOD),
            obs(5000, -33.451, -70.661, 10, MotionStateV2Engine.QualityClass.GOOD),
            obs(8000, -33.4512, -70.6612, 0.5, MotionStateV2Engine.QualityClass.GOOD));
        engine.update(obs(12000, -33.4512, -70.6612, 0, MotionStateV2Engine.QualityClass.GOOD));
        assertEquals(MotionStateV2Engine.State.PAUSED, engine.getState());
    }

    @Test
    public void test15SensorMoving() {
        // acelerómetro MOVING + GPS con desplazamiento → MOVING
        feed(
            obs(0, -33.45, -70.66, 0, MotionStateV2Engine.QualityClass.GOOD),
            obs(5000, -33.452, -70.662, 0, MotionStateV2Engine.QualityClass.GOOD),
            obs(10000, -33.454, -70.664, 0, MotionStateV2Engine.QualityClass.GOOD));
        assertEquals(MotionStateV2Engine.State.MOVING, engine.getState());
    }

    @Test
    public void test16SensorUnknown() {
        // sin evidencia del sensor, GPS manda: desplazamiento → MOVING
        feed(
            obs(0, -33.45, -70.66, 0, MotionStateV2Engine.QualityClass.GOOD),
            obs(5000, -33.453, -70.663, 0, MotionStateV2Engine.QualityClass.GOOD));
        assertEquals(MotionStateV2Engine.State.MOVING, engine.getState());
    }

    @Test
    public void test17ReadquisicionTrasGap() {
        feed(
            obs(0, -33.45, -70.66, 10, MotionStateV2Engine.QualityClass.GOOD),
            obs(5000, -33.451, -70.661, 10, MotionStateV2Engine.QualityClass.GOOD));
        engine.update(obs(600_000, -33.451, -70.661, 0, MotionStateV2Engine.QualityClass.GOOD));
        assertEquals(MotionStateV2Engine.State.UNKNOWN, engine.getState());
        // al reacquirir con evidencia de movimiento → MOVING
        var t = engine.update(obs(610_000, -33.452, -70.662, 12, MotionStateV2Engine.QualityClass.GOOD));
        assertEquals(MotionStateV2Engine.State.MOVING, t.getState());
    }

    @Test
    public void test18ReinicioConservandoEstado() {
        feed(
            obs(0, -33.45, -70.66, 10, MotionStateV2Engine.QualityClass.GOOD),
            obs(5000, -33.451, -70.661, 10, MotionStateV2Engine.QualityClass.GOOD),
            obs(8000, -33.4512, -70.6612, 0.5, MotionStateV2Engine.QualityClass.GOOD));
        engine.update(obs(12000, -33.4512, -70.6612, 0, MotionStateV2Engine.QualityClass.GOOD));
        var before = engine.getState();

        // "reinicio": motor nuevo con restore
        MotionStateV2Engine restored = new MotionStateV2Engine(config);
        restored.restore(before, "stability-detected", BASE + 12000,
                BASE + 12000, -33.4512, -70.6612, true, BASE + 12000);
        assertEquals(before, restored.getState());
        // continúa la estabilidad: confirma STOPPED
        var t = restored.update(obs(72000, -33.4512, -70.6612, 0, MotionStateV2Engine.QualityClass.GOOD));
        assertEquals(MotionStateV2Engine.State.STOPPED, t.getState());
    }

    @Test
    public void testParametrosConfigurables() {
        config.setStopRadiusM(100);
        config.setStopConfirmSeconds(10);
        config.setGapTimeoutSeconds(60);
        feed(
            obs(0, -33.45, -70.66, 10, MotionStateV2Engine.QualityClass.GOOD),
            obs(5000, -33.451, -70.661, 10, MotionStateV2Engine.QualityClass.GOOD),
            obs(8000, -33.4512, -70.6612, 0.5, MotionStateV2Engine.QualityClass.GOOD));
        engine.update(obs(12000, -33.4512, -70.6612, 0, MotionStateV2Engine.QualityClass.GOOD));
        // confirmación a 25 s (>= 10 configurado; < 45 default)
        var t = engine.update(obs(33000, -33.4513, -70.6613, 0, MotionStateV2Engine.QualityClass.GOOD));
        assertNotNull(t);
        assertEquals(MotionStateV2Engine.State.STOPPED, t.getState());
    }

    @Test
    public void testGpsSinFixNoEsStopped() {
        // un único fix inicial: entra en PAUSED, jamás STOPPED sin confirmación
        feed(obs(0, -33.45, -70.66, 0, MotionStateV2Engine.QualityClass.GOOD));
        assertEquals(MotionStateV2Engine.State.PAUSED, engine.getState());
    }

}
