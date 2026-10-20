package com.bm4a.platform.modules.packs.internal;

import java.util.List;

import com.bm4a.platform.modules.metadata.ModuleDefinitions.NewField;

import tools.jackson.databind.JsonNode;

/**
 * The pack document format. Module, field, role and relationship entries have the shape of the corresponding API
 * create requests; {@code layout}, role {@code permissions} and workflow {@code definition} are validated by their
 * owning modules.
 */
record PackDocument(String key, Integer version, String label, String description, JsonNode settings,
        List<PackModule> modules, List<PackRole> roles, List<PackRelationship> relationships,
        List<PackWorkflow> workflows) {

    PackDocument {
        modules = modules == null ? List.of() : modules;
        roles = roles == null ? List.of() : roles;
        relationships = relationships == null ? List.of() : relationships;
        workflows = workflows == null ? List.of() : workflows;
    }

    record PackModule(String apiKey, String labelSingular, String labelPlural, String description, JsonNode settings,
            List<NewField> fields, JsonNode layout) {

        PackModule {
            fields = fields == null ? List.of() : fields;
        }
    }

    record PackRole(String apiKey, String label, String description, JsonNode permissions) {
    }

    record PackRelationship(String apiKey, String label, String inverseLabel, String sourceModule,
            String targetModule, String cardinality, String onDelete) {
    }

    record PackWorkflow(String module, String field, JsonNode definition) {
    }
}
