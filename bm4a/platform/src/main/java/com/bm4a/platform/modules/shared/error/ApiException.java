package com.bm4a.platform.modules.shared.error;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * Base type for failures that map to a well-defined HTTP problem response. {@code code} is a stable,
 * machine-readable identifier that clients may switch on; {@code message} is safe to show to the caller.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<FieldViolation> violations;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(HttpStatus status, String code, String message, List<FieldViolation> violations) {
        super(message);
        this.status = status;
        this.code = code;
        this.violations = List.copyOf(violations);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<FieldViolation> violations() {
        return violations;
    }

    public static ApiException notFound(String resource) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", resource + " not found");
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    public static ApiException validation(List<FieldViolation> violations) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "Request validation failed", violations);
    }

    public static ApiException validation(String field, String code, String message) {
        return validation(List.of(new FieldViolation(field, code, message)));
    }

    public static ApiException preconditionRequired() {
        return new ApiException(HttpStatus.PRECONDITION_REQUIRED, "precondition_required",
                "If-Match header with the current version is required");
    }

    public static ApiException versionConflict(long currentVersion) {
        return new ApiException(HttpStatus.PRECONDITION_FAILED, "version_conflict",
                "Resource was modified concurrently; current version is " + currentVersion);
    }

    public static ApiException unavailable(String code, String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}
