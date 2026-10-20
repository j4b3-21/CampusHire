package com.bm4a.platform.modules.metadata.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bm4a.platform.modules.audit.AuditEntry;
import com.bm4a.platform.modules.audit.AuditLog;
import com.bm4a.platform.modules.authorization.ModuleRelation;
import com.bm4a.platform.modules.authorization.RelationshipTuple;
import com.bm4a.platform.modules.authorization.RelationshipTuplesChanged;
import com.bm4a.platform.modules.metadata.FieldStatus;
import com.bm4a.platform.modules.metadata.ModuleAccessMode;
import com.bm4a.platform.modules.metadata.internal.FieldDefinitionValidator.FieldDraft;
import com.bm4a.platform.modules.metadata.internal.VersionRow;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.error.FieldViolation;
import com.bm4a.platform.modules.shared.persistence.JsonColumns;
import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Module, field and layout definitions. Every change locks the module row, increments its definition version and
 * stores an immutable snapshot, all in one transaction together with the audit event.
 */
@Service
public class MetadataService {

    static final int MAX_FIELDS_PER_MODULE = 200;
    static final int MAX_MODULES_PER_SCOPE = 500;
    static final int MAX_SETTINGS_BYTES = 16 * 1024;

    private final MetadataRepository repository;
    private final FieldDefinitionValidator fieldValidator;
    private final LayoutValidator layoutValidator;
    private final AuditLog audit;
    private final ApplicationEventPublisher events;
    private final JsonColumns json;
    private final Clock clock;

    MetadataService(MetadataRepository repository, FieldDefinitionValidator fieldValidator,
            LayoutValidator layoutValidator, AuditLog audit, ApplicationEventPublisher events, JsonColumns json,
            Clock clock) {
        this.repository = repository;
        this.fieldValidator = fieldValidator;
        this.layoutValidator = layoutValidator;
        this.audit = audit;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    // ---- modules -------------------------------------------------------------------------------------------

    @Transactional
    public ModuleView createModule(MetadataScope scope, UUID actor, ModuleInput in, List<FieldInput> fields) {
        List<FieldViolation> violations = new ArrayList<>();
        if (in.apiKey() == null || !FieldDefinitionValidator.API_KEY.matcher(in.apiKey()).matches()) {
            violations.add(new FieldViolation("apiKey", "format", "apiKey must match ^[a-z][a-z0-9_]{0,62}$"));
        }
        validateLabels(in.labelSingular(), in.labelPlural(), in.description(), violations);
        ObjectNode settings = settings(in.settings(), violations);
        if (!violations.isEmpty()) {
            throw ApiException.validation(violations);
        }
        if (repository.countOwned(scope) >= MAX_MODULES_PER_SCOPE) {
            throw ApiException.conflict("limit_exceeded", "At most " + MAX_MODULES_PER_SCOPE + " modules per scope");
        }
        boolean collides = scope.isGlobal() ? repository.tenantKeyExists(in.apiKey())
                : repository.globalKeyExists(in.apiKey());
        if (collides || repository.findOwned(scope, in.apiKey(), false).isPresent()) {
            throw ApiException.conflict("module_exists", "A module with apiKey '" + in.apiKey() + "' already exists");
        }
        Instant now = SqlValues.now(clock);
        ModuleRow module = new ModuleRow(UUID.randomUUID(), scope.organizationId(), in.apiKey(),
                in.labelSingular().strip(), in.labelPlural().strip(), in.description(), ModuleRow.ACTIVE, settings,
                emptyLayout(), 1, now, actor, now, actor);
        try {
            repository.insertModule(module);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("module_exists", "A module with apiKey '" + in.apiKey() + "' already exists");
        }
        List<FieldInput> initial = fields == null ? List.of() : fields;
        if (initial.size() > MAX_FIELDS_PER_MODULE) {
            throw ApiException.validation("fields", "size", "at most " + MAX_FIELDS_PER_MODULE + " fields");
        }
        Set<String> keys = new HashSet<>();
        List<FieldDraft> drafts = new ArrayList<>();
        Function<String, Optional<ModuleRow>> targets = key -> key.equals(in.apiKey())
                ? Optional.of(module) : lookupTargets(scope).apply(key);
        for (int i = 0; i < initial.size(); i++) {
            String prefix = "fields[" + i + "].";
            try {
                FieldDraft draft = fieldValidator.validateNew(initial.get(i), targets, prefix);
                if (!keys.add(draft.apiKey())) {
                    violations.add(new FieldViolation(prefix + "apiKey", "duplicate", "duplicate field apiKey"));
                }
                drafts.add(draft);
            } catch (ApiException ex) {
                violations.addAll(ex.violations());
            }
        }
        if (!violations.isEmpty()) {
            throw ApiException.validation(violations);
        }
        drafts.forEach(draft -> repository.insertField(newField(module.id(), draft, actor, now)));
        snapshot(module, actor, now);
        syncTuples(Set.of(), module);
        audit.record(AuditEntry.of(scope.organizationId(), actor, "metadata.module.created", "module", module.id())
                .with("apiKey", module.apiKey()).with("fields", List.copyOf(keys)));
        return view(module);
    }

    @Transactional(readOnly = true)
    public List<ModuleView> listVisible(UUID organizationId, boolean includeArchived) {
        return repository.listVisible(organizationId, includeArchived).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<ModuleView> listGlobal(boolean includeArchived) {
        return repository.listGlobal(includeArchived).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public ModuleView getVisible(UUID organizationId, String apiKey) {
        return view(repository.findVisible(organizationId, apiKey).orElseThrow(() -> ApiException.notFound("Module")));
    }

    @Transactional(readOnly = true)
    public ModuleView getOwned(MetadataScope scope, String apiKey) {
        return view(repository.findOwned(scope, apiKey, false).orElseThrow(() -> ApiException.notFound("Module")));
    }

    @Transactional
    public ModuleView updateModule(MetadataScope scope, UUID actor, String apiKey, long expectedVersion,
            ModuleInput in) {
        ModuleRow current = lockOwned(scope, apiKey, expectedVersion);
        if (in.apiKey() != null && !in.apiKey().equals(current.apiKey())) {
            throw ApiException.validation("apiKey", "immutable", "apiKey cannot be changed");
        }
        List<FieldViolation> violations = new ArrayList<>();
        String singular = in.labelSingular() == null ? current.labelSingular() : in.labelSingular().strip();
        String plural = in.labelPlural() == null ? current.labelPlural() : in.labelPlural().strip();
        String description = in.description() == null ? current.description() : in.description();
        validateLabels(singular, plural, description, violations);
        ObjectNode settings = in.settings() == null ? current.settings() : settings(in.settings(), violations);
        if (!violations.isEmpty()) {
            throw ApiException.validation(violations);
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        diff(changes, "labelSingular", current.labelSingular(), singular);
        diff(changes, "labelPlural", current.labelPlural(), plural);
        diff(changes, "description", current.description(), description);
        if (!Objects.equals(current.settings(), settings)) {
            changes.put("settings", "changed");
            diff(changes, ModuleAccessMode.SETTING, ModuleAccessMode.of(current.settings()).orElse(null),
                    ModuleAccessMode.of(settings).orElse(null));
        }
        if (changes.isEmpty()) {
            return view(current);
        }
        ModuleRow updated = bump(new ModuleRow(current.id(), current.organizationId(), current.apiKey(), singular,
                plural, description, current.status(), settings, current.layout(), current.version(),
                current.createdAt(), current.createdBy(), current.updatedAt(), current.updatedBy()), actor);
        syncTuples(moduleTuples(current), updated);
        audit.record(AuditEntry.of(scope.organizationId(), actor, "metadata.module.updated", "module", current.id())
                .with("apiKey", current.apiKey()).with("changes", changes));
        return view(updated);
    }

    /** Archive or restore a module. Archived modules reject record writes; definitions and data are kept. */
    @Transactional
    public ModuleView setModuleStatus(MetadataScope scope, UUID actor, String apiKey, long expectedVersion,
            String status) {
        ModuleRow current = lockOwned(scope, apiKey, expectedVersion);
        if (current.status().equals(status)) {
            return view(current);
        }
        ModuleRow updated = bump(withStatus(current, status), actor);
        audit.record(AuditEntry.of(scope.organizationId(), actor,
                ModuleRow.ARCHIVED.equals(status) ? "metadata.module.archived" : "metadata.module.restored", "module",
                current.id()).with("apiKey", current.apiKey()));
        return view(updated);
    }

    @Transactional
    public ModuleView updateLayout(MetadataScope scope, UUID actor, String apiKey, long expectedVersion,
            JsonNode layout) {
        ModuleRow current = lockOwned(scope, apiKey, expectedVersion);
        ObjectNode canonical = layoutValidator.validate(layout, repository.findFields(current.id()));
        if (canonical.equals(current.layout())) {
            return view(current);
        }
        ModuleRow updated = bump(new ModuleRow(current.id(), current.organizationId(), current.apiKey(),
                current.labelSingular(), current.labelPlural(), current.description(), current.status(),
                current.settings(), canonical, current.version(), current.createdAt(), current.createdBy(),
                current.updatedAt(), current.updatedBy()), actor);
        audit.record(AuditEntry.of(scope.organizationId(), actor, "metadata.layout.updated", "module", current.id())
                .with("apiKey", current.apiKey()));
        return view(updated);
    }

    @Transactional(readOnly = true)
    public PageResponse<VersionRow> versions(UUID moduleId, PageQuery page) {
        return PageResponse.of(repository.listSnapshots(moduleId, page.offset(), page.size()), page,
                repository.countSnapshots(moduleId));
    }

    @Transactional(readOnly = true)
    public VersionRow version(UUID moduleId, long version) {
        return repository.findSnapshot(moduleId, version).orElseThrow(() -> ApiException.notFound("Module version"));
    }

    // ---- fields --------------------------------------------------------------------------------------------

    @Transactional
    public FieldRow addField(MetadataScope scope, UUID actor, String moduleKey, FieldInput in) {
        ModuleRow module = lockOwned(scope, moduleKey, null);
        List<FieldRow> existing = repository.findFields(module.id());
        if (existing.size() >= MAX_FIELDS_PER_MODULE) {
            throw ApiException.conflict("limit_exceeded", "At most " + MAX_FIELDS_PER_MODULE + " fields per module");
        }
        FieldDraft draft = fieldValidator.validateNew(in, selfOrScope(scope, module), "");
        if (existing.stream().anyMatch(field -> field.apiKey().equals(draft.apiKey()))) {
            throw ApiException.conflict("field_exists", "A field with apiKey '" + draft.apiKey() + "' already exists");
        }
        Instant now = SqlValues.now(clock);
        FieldRow field = newField(module.id(), draft, actor, now);
        repository.insertField(field);
        bump(module, actor);
        audit.record(AuditEntry.of(scope.organizationId(), actor, "metadata.field.created", "field", field.id())
                .with("module", module.apiKey()).with("apiKey", field.apiKey()).with("type", field.type().name()));
        return field;
    }

    @Transactional(readOnly = true)
    public FieldRow getField(UUID moduleId, String fieldKey) {
        return repository.findField(moduleId, fieldKey).orElseThrow(() -> ApiException.notFound("Field"));
    }

    @Transactional
    public FieldRow updateField(MetadataScope scope, UUID actor, String moduleKey, String fieldKey,
            long expectedVersion, FieldInput in) {
        ModuleRow module = lockOwned(scope, moduleKey, null);
        FieldRow current = fieldForWrite(module, fieldKey, expectedVersion);
        FieldDraft draft = fieldValidator.validateUpdate(current, in, selfOrScope(scope, module));
        FieldRow updated = new FieldRow(current.id(), current.moduleId(), current.apiKey(), draft.label(),
                draft.description(), draft.type(), current.status(), draft.required(), current.unique(),
                draft.readOnly(), draft.sensitive(), draft.searchable(), draft.filterable(), draft.sortable(),
                draft.defaultValue(), draft.config(), draft.position(), current.version() + 1, current.createdAt(),
                current.createdBy(), SqlValues.now(clock), actor);
        Map<String, Object> changes = fieldChanges(current, updated);
        if (changes.isEmpty()) {
            return current;
        }
        repository.updateField(updated);
        bump(module, actor);
        audit.record(AuditEntry.of(scope.organizationId(), actor, "metadata.field.updated", "field", current.id())
                .with("module", module.apiKey()).with("apiKey", current.apiKey()).with("changes", changes));
        return updated;
    }

    @Transactional
    public FieldRow setFieldStatus(MetadataScope scope, UUID actor, String moduleKey, String fieldKey,
            long expectedVersion, FieldStatus status) {
        ModuleRow module = lockOwned(scope, moduleKey, null);
        FieldRow current = fieldForWrite(module, fieldKey, expectedVersion);
        if (current.status() == status) {
            return current;
        }
        FieldRow updated = new FieldRow(current.id(), current.moduleId(), current.apiKey(), current.label(),
                current.description(), current.type(), status, current.required(), current.unique(),
                current.readOnly(), current.sensitive(), current.searchable(), current.filterable(),
                current.sortable(), current.defaultValue(), current.config(), current.position(),
                current.version() + 1, current.createdAt(), current.createdBy(), SqlValues.now(clock), actor);
        repository.updateField(updated);
        bump(module, actor);
        audit.record(AuditEntry.of(scope.organizationId(), actor, "metadata.field.status_changed", "field",
                current.id()).with("module", module.apiKey()).with("apiKey", current.apiKey())
                .with("from", current.status().name()).with("to", status.name()));
        return updated;
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    /**
     * Locks a module owned by the scope. A global module addressed through a tenant scope is visible but read-only
     * (403); anything else unknown is 404. {@code expectedVersion} {@code null} skips the version check (field
     * operations carry their own field version).
     */
    private ModuleRow lockOwned(MetadataScope scope, String apiKey, Long expectedVersion) {
        Optional<ModuleRow> owned = repository.findOwned(scope, apiKey, true);
        if (owned.isEmpty()) {
            if (!scope.isGlobal() && repository.findVisible(scope.organizationId(), apiKey).isPresent()) {
                throw ApiException.forbidden("global_module_readonly",
                        "Platform-global modules can only be changed by platform administrators");
            }
            throw ApiException.notFound("Module");
        }
        ModuleRow module = owned.get();
        if (expectedVersion != null && module.version() != expectedVersion) {
            throw ApiException.versionConflict(module.version());
        }
        return module;
    }

    private FieldRow fieldForWrite(ModuleRow module, String fieldKey, long expectedVersion) {
        FieldRow current = repository.findField(module.id(), fieldKey)
                .orElseThrow(() -> ApiException.notFound("Field"));
        if (current.version() != expectedVersion) {
            throw ApiException.versionConflict(current.version());
        }
        return current;
    }

    /** Lookup targets: tenant scopes may reference their own and global modules; global modules only global ones. */
    private Function<String, Optional<ModuleRow>> lookupTargets(MetadataScope scope) {
        return key -> scope.isGlobal() ? repository.findOwned(scope, key, false)
                : repository.findVisible(scope.organizationId(), key);
    }

    private Function<String, Optional<ModuleRow>> selfOrScope(MetadataScope scope, ModuleRow module) {
        return key -> key.equals(module.apiKey()) ? Optional.of(module) : lookupTargets(scope).apply(key);
    }

    private ModuleRow bump(ModuleRow module, UUID actor) {
        Instant now = SqlValues.now(clock);
        ModuleRow bumped = new ModuleRow(module.id(), module.organizationId(), module.apiKey(),
                module.labelSingular(), module.labelPlural(), module.description(), module.status(),
                module.settings(), module.layout(), module.version() + 1, module.createdAt(), module.createdBy(), now,
                actor);
        repository.updateModule(bumped);
        snapshot(bumped, actor, now);
        return bumped;
    }

    private void snapshot(ModuleRow module, UUID actor, Instant now) {
        JsonNode snapshot = json.mapper().valueToTree(view(module));
        repository.insertSnapshot(module.id(), module.version(), snapshot, actor, now);
    }

    private ModuleView view(ModuleRow module) {
        return new ModuleView(module, repository.findFields(module.id()));
    }

    private static ModuleRow withStatus(ModuleRow m, String status) {
        return new ModuleRow(m.id(), m.organizationId(), m.apiKey(), m.labelSingular(), m.labelPlural(),
                m.description(), status, m.settings(), m.layout(), m.version(), m.createdAt(), m.createdBy(),
                m.updatedAt(), m.updatedBy());
    }

    private static FieldRow newField(UUID moduleId, FieldDraft d, UUID actor, Instant now) {
        return new FieldRow(UUID.randomUUID(), moduleId, d.apiKey(), d.label().strip(), d.description(), d.type(),
                FieldStatus.ACTIVE, d.required(), d.unique(), d.readOnly(), d.sensitive(), d.searchable(),
                d.filterable(), d.sortable(), d.defaultValue(), d.config(), d.position(), 1, now, actor, now, actor);
    }

    private ObjectNode emptyLayout() {
        ObjectNode layout = json.newObject();
        layout.putArray("sections");
        return layout;
    }

    private ObjectNode settings(JsonNode settings, List<FieldViolation> violations) {
        if (settings == null || settings.isNull()) {
            return json.newObject();
        }
        if (!(settings instanceof ObjectNode object)) {
            violations.add(new FieldViolation("settings", "type", "settings must be a JSON object"));
            return json.newObject();
        }
        if (json.write(object).length() > MAX_SETTINGS_BYTES) {
            violations.add(new FieldViolation("settings", "size", "settings must not exceed 16 KiB"));
        }
        if (ModuleAccessMode.of(object).isEmpty()) {
            violations.add(new FieldViolation("settings." + ModuleAccessMode.SETTING, "invalid",
                    "accessMode must be MEMBERS or ROLES"));
        }
        return object;
    }

    /**
     * OpenFGA tuples derived from a tenant module (ADR-004): its organization, plus the organization's members as
     * readers and writers in {@code MEMBERS} mode. Platform-global modules have no module tuples; access to them is
     * decided per tenant by the access module.
     */
    static Set<RelationshipTuple> moduleTuples(ModuleRow module) {
        if (module.global()) {
            return Set.of();
        }
        Set<RelationshipTuple> tuples = new LinkedHashSet<>();
        tuples.add(RelationshipTuple.moduleOrganization(module.id(), module.organizationId()));
        if (ModuleAccessMode.of(module.settings()).orElse(ModuleAccessMode.ROLES) == ModuleAccessMode.MEMBERS) {
            tuples.add(RelationshipTuple.moduleMembers(ModuleRelation.READER, module.id(), module.organizationId()));
            tuples.add(RelationshipTuple.moduleMembers(ModuleRelation.WRITER, module.id(), module.organizationId()));
        }
        return tuples;
    }

    private void syncTuples(Set<RelationshipTuple> before, ModuleRow after) {
        RelationshipTuplesChanged change = RelationshipTuplesChanged.diff(before, moduleTuples(after));
        if (change.hasChanges()) {
            events.publishEvent(change);
        }
    }

    private static void validateLabels(String singular, String plural, String description,
            List<FieldViolation> violations) {
        if (singular == null || singular.isBlank() || singular.length() > 200) {
            violations.add(new FieldViolation("labelSingular", "size", "labelSingular must be 1-200 characters"));
        }
        if (plural == null || plural.isBlank() || plural.length() > 200) {
            violations.add(new FieldViolation("labelPlural", "size", "labelPlural must be 1-200 characters"));
        }
        if (description != null && description.length() > 2000) {
            violations.add(new FieldViolation("description", "size", "description must be at most 2000 characters"));
        }
    }

    private static void diff(Map<String, Object> changes, String name, Object from, Object to) {
        if (!Objects.equals(from, to)) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("from", from);
            change.put("to", to);
            changes.put(name, change);
        }
    }

    private static Map<String, Object> fieldChanges(FieldRow a, FieldRow b) {
        Map<String, Object> changes = new LinkedHashMap<>();
        diff(changes, "label", a.label(), b.label());
        diff(changes, "description", a.description(), b.description());
        diff(changes, "type", a.type(), b.type());
        diff(changes, "required", a.required(), b.required());
        diff(changes, "readOnly", a.readOnly(), b.readOnly());
        diff(changes, "sensitive", a.sensitive(), b.sensitive());
        diff(changes, "searchable", a.searchable(), b.searchable());
        diff(changes, "filterable", a.filterable(), b.filterable());
        diff(changes, "sortable", a.sortable(), b.sortable());
        diff(changes, "position", a.position(), b.position());
        if (!Objects.equals(a.defaultValue(), b.defaultValue())) {
            changes.put("defaultValue", "changed");
        }
        if (!Objects.equals(a.config(), b.config())) {
            changes.put("config", "changed");
        }
        return changes;
    }
}
