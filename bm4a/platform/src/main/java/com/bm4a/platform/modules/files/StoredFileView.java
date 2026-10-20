package com.bm4a.platform.modules.files;

import java.time.Instant;
import java.util.UUID;

/**
 * A stored file.
 *
 * @param name the sanitized original file name (display only; never used for storage)
 * @param contentType the type detected from the content, one of {@link ContentSniffer}'s allow-list
 * @param sha256 lower-case hex digest of the content
 */
public record StoredFileView(UUID id, UUID organizationId, String name, String contentType, long sizeBytes,
        String sha256, Instant createdAt, UUID createdBy) {
}
