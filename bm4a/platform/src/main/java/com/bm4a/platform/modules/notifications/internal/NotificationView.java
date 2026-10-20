package com.bm4a.platform.modules.notifications.internal;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/** A notification as returned by the API and pushed over the stream (always the caller's own). */
public record NotificationView(UUID id, UUID organizationId, String type, String title, String body, String link,
        JsonNode data, boolean read, Instant readAt, Instant createdAt) {
}
