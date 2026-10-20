package com.bm4a.platform.modules.metadata.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.metadata.ModuleSchemas;

@Service
class JdbcModuleSchemas implements ModuleSchemas {

    private final MetadataRepository repository;

    JdbcModuleSchemas(MetadataRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ModuleSchema> find(UUID organizationId, String moduleKey) {
        return repository.findVisible(organizationId, moduleKey).map(this::schema);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ModuleSchema> findById(UUID organizationId, UUID moduleId) {
        return repository.findVisibleById(organizationId, moduleId).map(this::schema);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModuleSchema> referencing(UUID organizationId, String moduleKey) {
        return repository.referencing(organizationId, moduleKey).stream().map(this::schema).toList();
    }

    private ModuleSchema schema(ModuleRow module) {
        return new ModuleSchema(module.id(), module.organizationId(), module.apiKey(), module.labelSingular(),
                module.labelPlural(), module.active(), module.version(),
                repository.findFields(module.id()).stream().map(FieldRow::schema).toList());
    }
}
