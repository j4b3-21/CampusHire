package com.bm4a.platform.modules.files;

import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

import com.bm4a.platform.modules.tenancy.TenantContext;

/**
 * File storage for other modules (ADR-005). Content is size-limited, its type is detected from magic bytes and must be
 * on the allow-list of {@link ContentSniffer}; the declared type and name are never trusted. These methods perform no
 * permission checks beyond the tenant scope: callers authorize the operation themselves.
 */
public interface Files {

    /**
     * Stores {@code content} as a file of the tenant, created by {@code tenant.userId()}. The stream is read to the end
     * but not closed.
     *
     * @param name original file name (sanitized for display)
     * @param declaredType client-declared media type; only distinguishes CSV from plain text
     * @throws com.bm4a.platform.modules.shared.error.ApiException 413 {@code file_too_large}, 415
     *             {@code unsupported_file_type}, 400 {@code empty_file}, 503 {@code file_storage_unavailable}
     */
    StoredFileView store(TenantContext tenant, String name, String declaredType, InputStream content);

    /** Like {@link #store(TenantContext, String, String, InputStream)} for content held in memory. */
    StoredFileView store(TenantContext tenant, String name, String declaredType, byte[] content);

    /** The content of an active file of the organization; the caller closes the stream. 404 when unknown. */
    InputStream open(UUID organizationId, UUID fileId);

    Optional<StoredFileView> find(UUID organizationId, UUID fileId);
}
