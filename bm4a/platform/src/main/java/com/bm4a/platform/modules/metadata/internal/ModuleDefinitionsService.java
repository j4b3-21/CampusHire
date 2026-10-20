package com.bm4a.platform.modules.metadata.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bm4a.platform.modules.metadata.FieldConfig;
import com.bm4a.platform.modules.metadata.FieldSchema;
import com.bm4a.platform.modules.metadata.ModuleDefinitions;
import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.metadata.ModuleSchemas;
import com.bm4a.platform.modules.shared.error.ApiException;

import tools.jackson.databind.JsonNode;

/** {@link ModuleDefinitions} on top of {@link MetadataService}, always in the organization's own scope. */
@Service
class ModuleDefinitionsService implements ModuleDefinitions {

    private final MetadataService metadata;
    private final ModuleSchemas schemas;

    ModuleDefinitionsService(MetadataService metadata, ModuleSchemas schemas) {
        this.metadata = metadata;
        this.schemas = schemas;
    }

    @Override
    public Optional<ModuleSchema> find(UUID organizationId, String moduleKey) {
        return schemas.find(organizationId, moduleKey);
    }

    @Override
    @Transactional
    public ModuleSchema createModule(UUID organizationId, UUID actorUserId, NewModule module) {
        ModuleView view = metadata.createModule(MetadataScope.tenant(organizationId), actorUserId,
                new ModuleInput(module.apiKey(), module.labelSingular(), module.labelPlural(), module.description(),
                        module.settings()),
                module.fields().stream().map(ModuleDefinitionsService::input).toList());
        return schema(organizationId, view.module().apiKey());
    }

    @Override
    @Transactional
    public FieldSchema addField(UUID organizationId, UUID actorUserId, String moduleKey, NewField field) {
        return metadata.addField(MetadataScope.tenant(organizationId), actorUserId, moduleKey, input(field)).schema();
    }

    @Override
    @Transactional
    public List<String> addOptions(UUID organizationId, UUID actorUserId, String moduleKey, String fieldKey,
            List<FieldConfig.SelectOption> options) {
        return metadata.appendOptions(MetadataScope.tenant(organizationId), actorUserId, moduleKey, fieldKey, options);
    }

    @Override
    @Transactional(readOnly = true)
    public JsonNode layout(UUID organizationId, String moduleKey) {
        return metadata.getVisible(organizationId, moduleKey).module().layout();
    }

    @Override
    @Transactional
    public ModuleSchema setLayout(UUID organizationId, UUID actorUserId, String moduleKey, JsonNode layout) {
        metadata.updateLayout(MetadataScope.tenant(organizationId), actorUserId, moduleKey, null, layout);
        return schema(organizationId, moduleKey);
    }

    private ModuleSchema schema(UUID organizationId, String moduleKey) {
        return schemas.find(organizationId, moduleKey).orElseThrow(() -> ApiException.notFound("Module"));
    }

    private static FieldInput input(NewField f) {
        return new FieldInput(f.apiKey(), f.label(), f.description(), f.type(), f.required(), f.unique(),
                f.readOnly(), f.sensitive(), f.searchable(), f.filterable(), f.sortable(), f.defaultValue(), f.config(),
                f.position());
    }
}
