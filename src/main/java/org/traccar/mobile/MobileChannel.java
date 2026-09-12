/*
 * Copyright 2026 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package org.traccar.mobile;

/**
 * Transporte por el que llegó un envelope al orquestador. MQTT y HTTP comparten
 * validación, idempotencia y persistencia, pero la dimensión MQTT de presencia
 * solo la mueve el tráfico MQTT (un batch HTTP no prueba que la sesión MQTT viva).
 */
public enum MobileChannel {
    MQTT,
    HTTP
}
