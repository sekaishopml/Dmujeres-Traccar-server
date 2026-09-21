package org.traccar.mobile;

import java.util.Objects;

/**
 * S1: validación de la clave móvil con VENTANA DE ROTACIÓN. El servidor acepta
 * la clave actual y, opcionalmente, la anterior mientras la flota se actualiza.
 * Comparación en tiempo constante para no filtrar prefijos por timing.
 */
public final class MobileApiKeyValidator {

    private MobileApiKeyValidator() {
    }

    public static boolean isValid(String provided, String current, String previous) {
        if (provided == null || current == null || current.isEmpty()) {
            return false;
        }
        if (constantTimeEquals(provided, current)) {
            return true;
        }
        return previous != null && !previous.isEmpty() && constantTimeEquals(provided, previous);
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] left = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] right = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (left.length != right.length) {
            // Aun con longitudes distintas se recorre el máximo para no filtrar
            // longitud por diferencia de tiempo; el resultado ya es false.
            int mismatch = left.length ^ right.length;
            int length = Math.max(left.length, right.length);
            for (int index = 0; index < length; index++) {
                byte x = index < left.length ? left[index] : 0;
                byte y = index < right.length ? right[index] : 0;
                mismatch |= x ^ y;
            }
            return mismatch == 0;
        }
        return Objects.equals(a, b) && java.security.MessageDigest.isEqual(left, right);
    }
}
