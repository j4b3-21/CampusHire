package com.bm4a.platform.modules.shared.error;

/**
 * A single field-level validation error. {@code field} is a JSON-pointer-like path such as {@code data.price}.
 */
public record FieldViolation(String field, String code, String message) {
}
