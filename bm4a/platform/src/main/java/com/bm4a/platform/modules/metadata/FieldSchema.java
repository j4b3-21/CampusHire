package com.bm4a.platform.modules.metadata;

import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Read model of one field definition.
 */
public record FieldSchema(UUID id, String apiKey, String label, FieldType type, FieldStatus status, boolean required,
        boolean unique, boolean readOnly, boolean sensitive, boolean searchable, boolean filterable, boolean sortable,
        JsonNode defaultValue, FieldConfig config, int position) {

    public boolean visible() {
        return status != FieldStatus.ARCHIVED;
    }

    public boolean writable() {
        return status == FieldStatus.ACTIVE;
    }
}
