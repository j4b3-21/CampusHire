package com.bm4a.platform.modules.audit;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Defensive redaction of audit details: any value whose key looks like a credential is replaced by
 * {@link AuditEntry#REDACTED}, recursively through maps and collections.
 */
public final class Redaction {

    private static final List<String> SENSITIVE_KEY_PARTS = List.of("password", "passwd", "secret", "token",
            "authorization", "apikey", "api_key", "credential", "private_key", "privatekey", "cookie");

    private Redaction() {
    }

    public static boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "_");
        return SENSITIVE_KEY_PARTS.stream().anyMatch(normalized::contains);
    }

    public static Map<String, Object> redact(Map<String, ?> details) {
        Map<String, Object> result = new LinkedHashMap<>();
        details.forEach((key, value) -> result.put(key, isSensitiveKey(key) ? AuditEntry.REDACTED : redactValue(value)));
        return result;
    }

    private static Object redactValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((k, v) -> nested.put(String.valueOf(k), v));
            return redact(nested);
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(Redaction::redactValue).toList();
        }
        return value;
    }
}
