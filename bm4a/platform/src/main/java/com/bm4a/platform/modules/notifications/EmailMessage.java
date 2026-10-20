package com.bm4a.platform.modules.notifications;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * An e-mail rendered from a Thymeleaf template pair under {@code templates/bm4a/mail/}: {@code <template>.html}
 * (using the branded {@code layout.html}) and {@code <template>.txt} (plain-text alternative). Every template sees
 * {@code appName}, {@code baseUrl} and {@code subject} besides its {@code model}. Applications may add template pairs.
 * <ul>
 * <li>{@value #GENERIC}: {@code title}, {@code paragraphs} (list of strings), optional {@code actionLabel} and
 * {@code actionUrl} (an absolute path is resolved against {@code bm4a.app.base-url})</li>
 * <li>{@value #INVITATION}: {@code organizationName}, {@code inviterName} (optional), {@code roleLabel},
 * {@code acceptUrl}, {@code expiresAt} (display text)</li>
 * </ul>
 *
 * @param subject 1-500 characters, single line
 */
public record EmailMessage(String subject, String template, Map<String, Object> model) {

    public static final String GENERIC = "generic";
    public static final String INVITATION = "invitation";

    private static final Pattern TEMPLATE = Pattern.compile("^[a-z][a-z0-9_-]{0,62}$");

    public EmailMessage {
        if (subject == null || subject.isBlank() || subject.length() > 500 || subject.contains("\r")
                || subject.contains("\n")) {
            throw new IllegalArgumentException("subject must be a single line of 1-500 characters");
        }
        if (template == null || !TEMPLATE.matcher(template).matches()) {
            throw new IllegalArgumentException("template must match " + TEMPLATE.pattern());
        }
        subject = subject.strip();
        model = model == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(model));
    }

    public static EmailMessage of(String subject, String template, Map<String, Object> model) {
        return new EmailMessage(subject, template, model);
    }

    /**
     * The {@value #GENERIC} template: a heading, paragraphs and an optional button ({@code actionLabel} and
     * {@code actionUrl} both {@code null} for none).
     */
    public static EmailMessage generic(String subject, String title, List<String> paragraphs, String actionLabel,
            String actionUrl) {
        if ((actionLabel == null) != (actionUrl == null)) {
            throw new IllegalArgumentException("actionLabel and actionUrl must be given together");
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("title", title);
        model.put("paragraphs", paragraphs == null ? List.of() : List.copyOf(paragraphs));
        model.put("actionLabel", actionLabel);
        model.put("actionUrl", actionUrl);
        return new EmailMessage(subject, GENERIC, model);
    }
}
