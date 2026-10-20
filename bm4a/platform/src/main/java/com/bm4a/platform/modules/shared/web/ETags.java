package com.bm4a.platform.modules.shared.web;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.bm4a.platform.modules.shared.error.ApiException;

/**
 * Optimistic concurrency helpers. Resource versions are exposed as strong ETags ({@code "3"}) and must be sent
 * back in {@code If-Match} for modifying requests.
 */
public final class ETags {

    private static final Pattern ENTITY_TAG = Pattern.compile("^\\s*(?:W/)?\"(\\d{1,18})\"\\s*$");

    private ETags() {
    }

    public static String of(long version) {
        return "\"" + version + "\"";
    }

    /**
     * Parses the expected version from an {@code If-Match} header. Missing header -> 428, malformed -> 400.
     */
    public static long requireVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw ApiException.preconditionRequired();
        }
        Matcher matcher = ENTITY_TAG.matcher(ifMatch);
        if (!matcher.matches()) {
            throw ApiException.badRequest("invalid_if_match", "If-Match must be a single entity tag such as \"3\"");
        }
        return Long.parseLong(matcher.group(1));
    }
}
