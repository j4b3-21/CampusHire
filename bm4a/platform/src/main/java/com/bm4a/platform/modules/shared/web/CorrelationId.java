package com.bm4a.platform.modules.shared.web;

import java.util.regex.Pattern;

import org.slf4j.MDC;

/**
 * Correlation ID of the current request, held in the logging MDC by {@link CorrelationIdFilter}.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private CorrelationId() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    static boolean isValid(String candidate) {
        return candidate != null && VALID.matcher(candidate).matches();
    }
}
