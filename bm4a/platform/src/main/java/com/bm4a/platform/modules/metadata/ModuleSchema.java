package com.bm4a.platform.modules.metadata;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read model of a module and all of its fields (including archived ones, which callers must hide).
 *
 * @param organizationId owning tenant, or {@code null} for a platform-global module
 * @param version definition version; increments on any module, field or layout change
 */
public record ModuleSchema(UUID id, UUID organizationId, String apiKey, String labelSingular, String labelPlural,
        boolean active, long version, List<FieldSchema> fields) {

    public ModuleSchema {
        fields = List.copyOf(fields);
    }

    public boolean global() {
        return organizationId == null;
    }

    public Optional<FieldSchema> field(String apiKey) {
        return fields.stream().filter(field -> field.apiKey().equals(apiKey)).findFirst();
    }
}
