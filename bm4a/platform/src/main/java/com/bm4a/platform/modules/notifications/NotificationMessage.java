package com.bm4a.platform.modules.notifications;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Content of a notification. Plain text only: clients render {@code title} and {@code body} as text, never as HTML.
 *
 * @param type machine-readable kind for clients (icons, grouping), e.g. {@code workflow.transitioned};
 *        {@code ^[a-z][a-z0-9_.-]{0,99}$}
 * @param title 1-200 characters
 * @param body optional, up to 4000 characters; blank lines separate paragraphs in e-mail
 * @param link optional target in the web application: an absolute path such as {@code /app/<orgId>/m/drive/<id>}
 *        (resolved against {@code bm4a.app.base-url} in e-mail) or an {@code http(s)} URL
 * @param data optional JSON-serializable values for clients (at most 50 entries)
 */
public record NotificationMessage(String type, String title, String body, String link, Map<String, Object> data) {

    private static final Pattern TYPE = Pattern.compile("^[a-z][a-z0-9_.-]{0,99}$");
    private static final Pattern ABSOLUTE_URL = Pattern.compile("^https?://[^\\s]+$");

    public NotificationMessage {
        if (type == null || !TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("type must match " + TYPE.pattern());
        }
        if (title == null || title.isBlank() || title.length() > 200) {
            throw new IllegalArgumentException("title must be 1-200 characters");
        }
        if (body != null && body.length() > 4000) {
            throw new IllegalArgumentException("body must be at most 4000 characters");
        }
        if (link != null && (link.length() > 2000 || !(isPath(link) || ABSOLUTE_URL.matcher(link).matches()))) {
            throw new IllegalArgumentException("link must be an absolute path or an http(s) URL of at most 2000 "
                    + "characters");
        }
        if (data != null && data.size() > 50) {
            throw new IllegalArgumentException("data must have at most 50 entries");
        }
        title = title.strip();
        body = body == null || body.isBlank() ? null : body.strip();
        data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    public static NotificationMessage of(String type, String title, String body, String link) {
        return new NotificationMessage(type, title, body, link, null);
    }

    public NotificationMessage withData(Map<String, Object> data) {
        return new NotificationMessage(type, title, body, link, data);
    }

    private static boolean isPath(String link) {
        return link.startsWith("/") && !link.startsWith("//") && link.chars().noneMatch(Character::isWhitespace);
    }
}
