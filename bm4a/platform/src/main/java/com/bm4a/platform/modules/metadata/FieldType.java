package com.bm4a.platform.modules.metadata;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Supported field types. The canonical JSON encoding of each is documented in ADR-001 and enforced by
 * {@link FieldValues}.
 */
public enum FieldType {

    TEXT, LONG_TEXT, INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME, EMAIL, PHONE, URL, CURRENCY, SINGLE_SELECT,
    MULTI_SELECT, JSON, FILE_REFERENCE, IMAGE_REFERENCE, LOOKUP;

    private static final Set<FieldType> UNIQUE_CAPABLE = EnumSet.of(TEXT, EMAIL, PHONE, URL, INTEGER, LOOKUP);
    private static final Set<FieldType> NOT_FILTERABLE = EnumSet.of(JSON, FILE_REFERENCE, IMAGE_REFERENCE);
    private static final Set<FieldType> NOT_SORTABLE = EnumSet.of(LONG_TEXT, MULTI_SELECT, JSON, FILE_REFERENCE,
            IMAGE_REFERENCE);
    private static final Set<FieldType> NO_DEFAULT = EnumSet.of(LOOKUP, FILE_REFERENCE, IMAGE_REFERENCE);

    /**
     * Type changes that keep every previously valid stored value valid and identically encoded. Anything else needs a
     * new field plus a backfill.
     */
    private static final Map<FieldType, Set<FieldType>> COMPATIBLE_CHANGES = Map.of(
            TEXT, EnumSet.of(LONG_TEXT),
            EMAIL, EnumSet.of(TEXT, LONG_TEXT),
            PHONE, EnumSet.of(TEXT, LONG_TEXT),
            URL, EnumSet.of(TEXT, LONG_TEXT),
            INTEGER, EnumSet.of(DECIMAL));

    public boolean supportsUnique() {
        return UNIQUE_CAPABLE.contains(this);
    }

    public boolean supportsFilter() {
        return !NOT_FILTERABLE.contains(this);
    }

    public boolean supportsSort() {
        return !NOT_SORTABLE.contains(this);
    }

    public boolean supportsDefault() {
        return !NO_DEFAULT.contains(this);
    }

    public boolean canChangeTo(FieldType target) {
        return this == target || COMPATIBLE_CHANGES.getOrDefault(this, Set.of()).contains(target);
    }
}
