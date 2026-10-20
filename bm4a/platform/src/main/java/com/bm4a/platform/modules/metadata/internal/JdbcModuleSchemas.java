package com.bm4a.platform.modules.metadata.internal;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bm4a.platform.modules.metadata.ModuleAccessMode;
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
    public List<ModuleSchema> listActive(UUID organizationId) {
        return repository.listVisible(organizationId, false).stream().map(this::schema)
                .sorted(Comparator.comparing(ModuleSchema::apiKey)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModuleSchema> referencing(UUID organizationId, String moduleKey) {
        return repository.referencing(organizationId, moduleKey).stream().map(this::schema).toList();
    }

    /** An unreadable stored access mode is treated as the restrictive {@code ROLES} mode (fail closed). */
    private ModuleSchema schema(ModuleRow module) {
        return new ModuleSchema(module.id(), module.organizationId(), module.apiKey(), module.labelSingular(),
                module.labelPlural(), module.active(), module.version(),
                ModuleAccessMode.of(module.settings()).orElse(ModuleAccessMode.ROLES),
                repository.findFields(module.id()).stream().map(FieldRow::schema).toList());
    }
}
