package com.bm4a.platform.modules.metadata.internal;

import java.time.Instant;
import java.util.UUID;

import com.bm4a.platform.modules.metadata.FieldConfig;
import com.bm4a.platform.modules.metadata.FieldSchema;
import com.bm4a.platform.modules.metadata.FieldStatus;
import com.bm4a.platform.modules.metadata.FieldType;

import tools.jackson.databind.JsonNode;

public record FieldRow(UUID id, UUID moduleId, String apiKey, String label, String description, FieldType type,
        FieldStatus status, boolean required, boolean unique, boolean readOnly, boolean sensitive, boolean searchable,
        boolean filterable, boolean sortable, JsonNode defaultValue, FieldConfig config, int position, long version,
        Instant createdAt, UUID createdBy, Instant updatedAt, UUID updatedBy) {

    public FieldSchema schema() {
        return new FieldSchema(id, apiKey, label, type, status, required, unique, readOnly, sensitive, searchable,
                filterable, sortable, defaultValue, config, position);
    }
}
