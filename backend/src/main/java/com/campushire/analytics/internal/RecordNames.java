package com.campushire.analytics.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.metadata.ModuleSchemas;
import com.bm4a.platform.modules.records.RecordReader;
import com.bm4a.platform.modules.records.RecordView;

import tools.jackson.databind.JsonNode;

/** Display names of department, batch and company records, resolved in one batch read per module. */
@Component
class RecordNames {

    private static final String NAME_FIELD = "name";

    private final ModuleSchemas schemas;
    private final RecordReader records;

    RecordNames(ModuleSchemas schemas, RecordReader records) {
        this.schemas = schemas;
        this.records = records;
    }

    /** Names by record ID; IDs of missing, deleted or other modules' records are absent. */
    Map<UUID, String> of(UUID organizationId, String moduleKey, Collection<UUID> ids) {
        List<UUID> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        Optional<UUID> moduleId = schemas.find(organizationId, moduleKey).map(ModuleSchema::id);
        if (wanted.isEmpty() || moduleId.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        for (RecordView record : records.findActive(organizationId, wanted)) {
            JsonNode name = record.data().path(NAME_FIELD);
            if (record.moduleId().equals(moduleId.get()) && name.isString()) {
                names.put(record.id(), name.asString());
            }
        }
        return names;
    }
}
