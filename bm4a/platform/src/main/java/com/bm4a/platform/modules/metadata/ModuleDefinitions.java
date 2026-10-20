package com.bm4a.platform.modules.metadata;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Tenant module definitions for trusted application code (for example app pack installers). <strong>No permission
 * checks are performed</strong>; {@code actorUserId} must be an existing user and is recorded as author. Everything
 * else is identical to the metadata API: validation (failures are {@code ApiException}s with the API's codes and
 * field paths), definition versions and snapshots, audit and OpenFGA module tuples. Only modules owned by the
 * organization can be changed (platform-global ones are 403 {@code global_module_readonly}).
 */
public interface ModuleDefinitions {

    /** A module visible to the organization: its own or a platform-global one. */
    Optional<ModuleSchema> find(UUID organizationId, String moduleKey);

    /** 409 {@code module_exists} when the key is taken. Lookup fields may target the module itself. */
    ModuleSchema createModule(UUID organizationId, UUID actorUserId, NewModule module);

    /** 409 {@code field_exists} when the module already has a field with the key. */
    FieldSchema addField(UUID organizationId, UUID actorUserId, String moduleKey, NewField field);

    /**
     * Appends the options of a select field whose values are not present yet (existing options, including deprecated
     * ones, are never changed). Returns the values added, in order; no change when empty.
     */
    List<String> addOptions(UUID organizationId, UUID actorUserId, String moduleKey, String fieldKey,
            List<FieldConfig.SelectOption> options);

    /** The current layout ({@code {"sections": [...]}}). */
    JsonNode layout(UUID organizationId, String moduleKey);

    /** Replaces the layout (validated like {@code PUT .../layout}). */
    ModuleSchema setLayout(UUID organizationId, UUID actorUserId, String moduleKey, JsonNode layout);

    /** Same shape as the module API's create request. */
    record NewModule(String apiKey, String labelSingular, String labelPlural, String description, JsonNode settings,
            List<NewField> fields) {

        public NewModule {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    /** Same shape as the field API's create request; {@code null} flags are {@code false}. */
    record NewField(String apiKey, String label, String description, FieldType type, Boolean required,
            Boolean unique, Boolean readOnly, Boolean sensitive, Boolean searchable, Boolean filterable,
            Boolean sortable, JsonNode defaultValue, JsonNode config, Integer position) {
    }
}
