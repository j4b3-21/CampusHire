package com.bm4a.platform.modules.dataexchange.internal;

/**
 * Formula (CSV) injection defense: text that a spreadsheet would evaluate gets a leading apostrophe on export, and
 * that apostrophe is removed again on import, so exported files round-trip.
 */
final class Cells {

    private Cells() {
    }

    static String escape(String value) {
        return value != null && !value.isEmpty() && triggersFormula(value.charAt(0)) ? "'" + value : value;
    }

    static String unescape(String value) {
        return value.length() > 1 && value.charAt(0) == '\'' && triggersFormula(value.charAt(1)) ? value.substring(1)
                : value;
    }

    private static boolean triggersFormula(char c) {
        return c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r';
    }
}
