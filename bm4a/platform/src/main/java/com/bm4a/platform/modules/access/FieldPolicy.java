package com.bm4a.platform.modules.access;

import java.util.Set;

/**
 * Field security of a role on one module (ADR-004).
 *
 * @param hiddenFields removed from every response (records, versions, related records) and rejected on write
 *            ({@code field_hidden}); hidden fields cannot be filtered, sorted or searched either
 * @param readOnlyFields may not be set on create or changed on update ({@code 403 field_read_only}); sending the
 *            unchanged value is allowed
 */
public record FieldPolicy(Set<String> hiddenFields, Set<String> readOnlyFields) {

    private static final FieldPolicy UNRESTRICTED = new FieldPolicy(Set.of(), Set.of());

    public FieldPolicy {
        hiddenFields = hiddenFields == null ? Set.of() : Set.copyOf(hiddenFields);
        readOnlyFields = readOnlyFields == null ? Set.of() : Set.copyOf(readOnlyFields);
    }

    public static FieldPolicy unrestricted() {
        return UNRESTRICTED;
    }

    public boolean hidden(String fieldKey) {
        return hiddenFields.contains(fieldKey);
    }

    public boolean readOnly(String fieldKey) {
        return readOnlyFields.contains(fieldKey);
    }
}
