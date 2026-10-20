package com.bm4a.platform.modules.metadata.internal;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.bm4a.platform.modules.metadata.ModuleAccessMode;
import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.metadata.ModuleSchemas;

/**
 * Lookups go through {@link SchemaCache}; the database is read (in a read-only transaction, or the caller's) only on a
 * miss, so cache hits need no connection.
 */
@Service
class JdbcModuleSchemas implements ModuleSchemas {

    private final MetadataRepository repository;
    private final SchemaCache cache;
    private final TransactionTemplate readOnly;

    JdbcModuleSchemas(MetadataRepository repository, SchemaCache cache, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.cache = cache;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public Optional<ModuleSchema> find(UUID organizationId, String moduleKey) {
        Supplier<Optional<ModuleSchema>> load = () -> read(
                () -> repository.findVisible(organizationId, moduleKey).map(this::schema));
        if (moduleKey == null || !FieldDefinitionValidator.API_KEY.matcher(moduleKey).matches()) {
            return load.get();
        }
        return cache.module(organizationId, "k:" + moduleKey, load);
    }

    @Override
    public Optional<ModuleSchema> findById(UUID organizationId, UUID moduleId) {
        Supplier<Optional<ModuleSchema>> load = () -> read(
                () -> repository.findVisibleById(organizationId, moduleId).map(this::schema));
        return moduleId == null ? load.get() : cache.module(organizationId, "i:" + moduleId, load);
    }

    @Override
    public List<ModuleSchema> listActive(UUID organizationId) {
        return cache.modules(organizationId, "active", () -> read(() -> repository.listVisible(organizationId, false)
                .stream().map(this::schema).sorted(Comparator.comparing(ModuleSchema::apiKey)).toList()));
    }

    @Override
    public List<ModuleSchema> referencing(UUID organizationId, String moduleKey) {
        Supplier<List<ModuleSchema>> load = () -> read(
                () -> repository.referencing(organizationId, moduleKey).stream().map(this::schema).toList());
        if (moduleKey == null || !FieldDefinitionValidator.API_KEY.matcher(moduleKey).matches()) {
            return load.get();
        }
        return cache.modules(organizationId, "ref:" + moduleKey, load);
    }

    private <T> T read(Supplier<T> query) {
        return readOnly.execute(status -> query.get());
    }

    /** An unreadable stored access mode is treated as the restrictive {@code ROLES} mode (fail closed). */
    private ModuleSchema schema(ModuleRow module) {
        return new ModuleSchema(module.id(), module.organizationId(), module.apiKey(), module.labelSingular(),
                module.labelPlural(), module.active(), module.version(),
                ModuleAccessMode.of(module.settings()).orElse(ModuleAccessMode.ROLES),
                repository.findFields(module.id()).stream().map(FieldRow::schema).toList());
    }
}
