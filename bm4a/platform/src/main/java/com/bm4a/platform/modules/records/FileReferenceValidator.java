package com.bm4a.platform.modules.records;

import java.util.Optional;
import java.util.UUID;

/**
 * Checks the {@code fileId} of {@code FILE_REFERENCE} and {@code IMAGE_REFERENCE} values on every record create and
 * update, inside the write transaction, for the fields whose value changes. Provided by the module that stores files;
 * without an implementation file references are only validated for their shape.
 */
public interface FileReferenceValidator {

    /**
     * @param image the field is an {@code IMAGE_REFERENCE}: the file must be an image
     * @return why the file cannot be referenced, or empty when it can
     */
    Optional<Rejection> validate(UUID organizationId, String fileId, boolean image);

    /** @param code stable violation code, for example {@code invalid_reference} */
    record Rejection(String code, String message) {
    }
}
