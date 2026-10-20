package com.bm4a.platform.modules.metadata.internal;

import com.bm4a.platform.modules.metadata.FieldType;

import tools.jackson.databind.JsonNode;

/**
 * Field definition input. For creation, {@code apiKey}, {@code label} and {@code type} are required; for updates
 * every {@code null} property means "unchanged" and an explicit JSON {@code null} {@code defaultValue} clears the
 * default.
 */
public record FieldInput(String apiKey, String label, String description, FieldType type, Boolean required,
        Boolean unique, Boolean readOnly, Boolean sensitive, Boolean searchable, Boolean filterable, Boolean sortable,
        JsonNode defaultValue, JsonNode config, Integer position) {
}
