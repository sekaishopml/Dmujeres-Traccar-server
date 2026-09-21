/* Copyright 2026 Anton Tananaev (anton@traccar.org) */
package org.traccar.mobile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1: la clave móvil acepta la actual y, durante la ventana de rotación, la
 * anterior. Sin clave configurada no se acepta nada (fail closed).
 */
public class MobileApiKeyValidatorTest {

    @Test
    public void testCurrentKeyAccepted() {
        assertTrue(MobileApiKeyValidator.isValid("nueva", "nueva", "vieja"));
    }

    @Test
    public void testPreviousKeyAcceptedDuringRotation() {
        assertTrue(MobileApiKeyValidator.isValid("vieja", "nueva", "vieja"));
    }

    @Test
    public void testUnknownRejected() {
        assertFalse(MobileApiKeyValidator.isValid("otra", "nueva", "vieja"));
        assertFalse(MobileApiKeyValidator.isValid("", "nueva", "vieja"));
        assertFalse(MobileApiKeyValidator.isValid(null, "nueva", "vieja"));
    }

    @Test
    public void testEmptyPreviousMeansStrictRotation() {
        assertFalse(MobileApiKeyValidator.isValid("vieja", "nueva", ""));
        assertFalse(MobileApiKeyValidator.isValid("vieja", "nueva", null));
        assertTrue(MobileApiKeyValidator.isValid("nueva", "nueva", null));
    }

    @Test
    public void testNoCurrentConfiguredFailsClosed() {
        assertFalse(MobileApiKeyValidator.isValid("cualquiera", null, "vieja"));
        assertFalse(MobileApiKeyValidator.isValid("cualquiera", "", "vieja"));
    }

    @Test
    public void testPrefixIsNotAccepted() {
        assertFalse(MobileApiKeyValidator.isValid("nue", "nueva", null));
        assertFalse(MobileApiKeyValidator.isValid("nuevalarga", "nueva", null));
    }
}
