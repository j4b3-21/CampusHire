package com.bm4a.platform.modules.metadata.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;

import com.bm4a.platform.modules.metadata.FieldConfig;
import com.bm4a.platform.modules.metadata.FieldStatus;
import com.bm4a.platform.modules.metadata.FieldType;
import com.bm4a.platform.modules.metadata.internal.FieldInput;
import com.bm4a.platform.modules.metadata.internal.FieldRow;
import com.bm4a.platform.modules.metadata.internal.VersionRow;
import com.bm4a.platform.modules.metadata.internal.ModuleInput;
import com.bm4a.platform.modules.metadata.internal.ModuleRow;
import com.bm4a.platform.modules.metadata.internal.ModuleView;
import com.bm4a.platform.modules.shared.web.ETags;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

final class MetadataDtos {

    private MetadataDtos() {
    }

    record CreateModuleRequest(String apiKey, String labelSingular, String labelPlural, String description,
            JsonNode settings, @Valid @Size(max = 200) List<FieldInput> fields) {

        ModuleInput input() {
            return new ModuleInput(apiKey, labelSingular, labelPlural, description, settings);
        }
    }

    record UpdateModuleRequest(String labelSingular, String labelPlural, String description, JsonNode settings) {

        ModuleInput input() {
            return new ModuleInput(null, labelSingular, labelPlural, description, settings);
        }
    }

    record FieldResponse(UUID id, String apiKey, String label, String description, FieldType type, FieldStatus status,
            boolean required, boolean unique, boolean readOnly, boolean sensitive, boolean searchable,
            boolean filterable, boolean sortable, JsonNode defaultValue, FieldConfig config, int position,
            long version, Instant createdAt, Instant updatedAt) {

        static FieldResponse of(FieldRow f) {
            return new FieldResponse(f.id(), f.apiKey(), f.label(), f.description(), f.type(), f.status(),
                    f.required(), f.unique(), f.readOnly(), f.sensitive(), f.searchable(), f.filterable(),
                    f.sortable(), f.defaultValue(), f.config(), f.position(), f.version(), f.createdAt(),
                    f.updatedAt());
        }
    }

    record ModuleResponse(UUID id, String apiKey, String scope, String labelSingular, String labelPlural,
            String description, String status, JsonNode settings, JsonNode layout, long version,
            List<FieldResponse> fields, Instant createdAt, Instant updatedAt) {

        static ModuleResponse of(ModuleView view) {
            ModuleRow m = view.module();
            return new ModuleResponse(m.id(), m.apiKey(), m.global() ? "GLOBAL" : "TENANT", m.labelSingular(),
                    m.labelPlural(), m.description(), m.status(), m.settings(), m.layout(), m.version(),
                    view.fields().stream().map(FieldResponse::of).toList(), m.createdAt(), m.updatedAt());
        }
    }

    record VersionResponse(long version, JsonNode snapshot, Instant createdAt, UUID createdBy) {

        static VersionResponse of(VersionRow row) {
            return new VersionResponse(row.version(), row.snapshot(), row.createdAt(), row.createdBy());
        }
    }

    static ResponseEntity<ModuleResponse> module(ModuleView view) {
        return ResponseEntity.ok().eTag(ETags.of(view.module().version())).body(ModuleResponse.of(view));
    }

    static ResponseEntity<FieldResponse> field(FieldRow field) {
        return ResponseEntity.ok().eTag(ETags.of(field.version())).body(FieldResponse.of(field));
    }
}
