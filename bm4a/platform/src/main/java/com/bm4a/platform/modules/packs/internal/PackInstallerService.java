package com.bm4a.platform.modules.packs.internal;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bm4a.platform.modules.access.RoleDefinitions;
import com.bm4a.platform.modules.audit.AuditEntry;
import com.bm4a.platform.modules.audit.AuditLog;
import com.bm4a.platform.modules.metadata.FieldConfig;
import com.bm4a.platform.modules.metadata.FieldSchema;
import com.bm4a.platform.modules.metadata.FieldType;
import com.bm4a.platform.modules.metadata.ModuleDefinitions;
import com.bm4a.platform.modules.metadata.ModuleDefinitions.NewField;
import com.bm4a.platform.modules.metadata.ModuleDefinitions.NewModule;
import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.packs.InstalledPack;
import com.bm4a.platform.modules.packs.PackInstallReport;
import com.bm4a.platform.modules.packs.PackInstallReport.Created;
import com.bm4a.platform.modules.packs.PackInstallReport.Skipped;
import com.bm4a.platform.modules.packs.PackInstallReport.Status;
import com.bm4a.platform.modules.packs.PackInstaller;
import com.bm4a.platform.modules.packs.internal.PackDocument.PackModule;
import com.bm4a.platform.modules.packs.internal.PackDocument.PackRelationship;
import com.bm4a.platform.modules.packs.internal.PackDocument.PackRole;
import com.bm4a.platform.modules.packs.internal.PackDocument.PackWorkflow;
import com.bm4a.platform.modules.relationships.RelationshipDefinitions;
import com.bm4a.platform.modules.relationships.RelationshipDefinitions.RelationshipDefinition;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.error.FieldViolation;
import com.bm4a.platform.modules.shared.persistence.JsonColumns;
import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.bm4a.platform.modules.tenancy.OrganizationSettings;
import com.bm4a.platform.modules.workflow.Workflows;

import tools.jackson.databind.JsonNode;

@Service
class PackInstallerService implements PackInstaller {

    private final PackReader reader;
    private final PackRepository repository;
    private final ModuleDefinitions modules;
    private final RoleDefinitions roles;
    private final RelationshipDefinitions relationships;
    private final Workflows workflows;
    private final OrganizationSettings settings;
    private final AuditLog audit;
    private final JsonColumns json;
    private final Clock clock;

    PackInstallerService(PackReader reader, PackRepository repository, ModuleDefinitions modules,
            RoleDefinitions roles, RelationshipDefinitions relationships, Workflows workflows,
            OrganizationSettings settings, AuditLog audit, JsonColumns json, Clock clock) {
        this.reader = reader;
        this.repository = repository;
        this.modules = modules;
        this.roles = roles;
        this.relationships = relationships;
        this.workflows = workflows;
        this.settings = settings;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PackInstallReport install(UUID organizationId, UUID actorUserId, JsonNode pack) {
        PackDocument document = reader.read(pack);
        String sha256 = sha256(json.mapper().writeValueAsBytes(pack));
        settings.get(organizationId);
        repository.lockOrganization(organizationId);
        Optional<InstalledPack> installed = repository.find(organizationId, document.key());
        if (installed.isPresent() && installed.get().version() > document.version()) {
            throw ApiException.conflict("pack_downgrade", "Version " + installed.get().version() + " of pack '"
                    + document.key() + "' is installed; version " + document.version() + " cannot replace it");
        }
        if (installed.isPresent() && installed.get().version() == document.version()) {
            List<Skipped> skipped = installed.get().definitionSha256().equals(sha256) ? List.of()
                    : List.of(new Skipped("pack", document.key(), "changed_without_version_bump"));
            return new PackInstallReport(document.key(), document.version(), Status.UP_TO_DATE, document.version(),
                    Created.none(), skipped);
        }
        Install run = new Install(organizationId, actorUserId);
        run.modules(document.modules());
        run.fields(document.modules());
        run.layouts(document.modules());
        run.roles(document.roles());
        run.relationships(document.relationships());
        run.workflows(document.workflows());
        List<String> addedSettings = settings.mergeDefaults(organizationId, actorUserId, document.settings());

        Status status = installed.isPresent() ? Status.UPGRADED : Status.INSTALLED;
        Integer previous = installed.map(InstalledPack::version).orElse(null);
        PackInstallReport report = new PackInstallReport(document.key(), document.version(), status, previous,
                new Created(run.createdModules, run.createdFields, run.addedOptions, run.layouts, run.createdRoles,
                        run.createdRelationships, run.createdWorkflows, addedSettings),
                run.skipped);
        Instant now = SqlValues.now(clock);
        repository.upsert(organizationId, new InstalledPack(document.key(), document.version(), document.label().strip(),
                sha256, now, actorUserId));
        repository.insertHistory(organizationId, document.key(), previous, document.version(), status.name(), sha256,
                json.mapper().valueToTree(report), now, actorUserId);
        audit.record(AuditEntry.of(organizationId, actorUserId,
                status == Status.INSTALLED ? "pack.installed" : "pack.upgraded", "pack", document.key())
                .with("version", document.version()).with("previousVersion", previous)
                .with("modules", run.createdModules).with("fields", run.createdFields.size())
                .with("roles", run.createdRoles).with("workflows", run.createdWorkflows));
        return report;
    }

    @Override
    @Transactional(readOnly = true)
    public List<InstalledPack> installed(UUID organizationId) {
        return repository.list(organizationId);
    }

    /** State of one install run. */
    private final class Install {

        private final UUID organizationId;
        private final UUID actor;
        private final Set<String> newModules = new LinkedHashSet<>();
        private final List<String> createdModules = new ArrayList<>();
        private final List<String> createdFields = new ArrayList<>();
        private final List<String> addedOptions = new ArrayList<>();
        private final List<String> layouts = new ArrayList<>();
        private final List<String> createdRoles = new ArrayList<>();
        private final List<String> createdRelationships = new ArrayList<>();
        private final List<String> createdWorkflows = new ArrayList<>();
        private final List<Skipped> skipped = new ArrayList<>();

        Install(UUID organizationId, UUID actor) {
            this.organizationId = organizationId;
            this.actor = actor;
        }

        /** Step 1: missing modules, without lookup fields (their targets may not exist yet). */
        void modules(List<PackModule> pack) {
            for (int i = 0; i < pack.size(); i++) {
                PackModule module = pack.get(i);
                String path = "modules[" + i + "]";
                Optional<ModuleSchema> existing = modules.find(organizationId, module.apiKey());
                if (existing.isPresent()) {
                    if (existing.get().global()) {
                        throw ApiException.validation(path + ".apiKey", "global_module",
                                "'" + module.apiKey() + "' is a platform-global module and cannot be extended");
                    }
                    continue;
                }
                List<NewField> fields = new ArrayList<>();
                for (int j = 0; j < module.fields().size(); j++) {
                    if (module.fields().get(j).type() != FieldType.LOOKUP) {
                        fields.add(positioned(module.fields().get(j), j));
                    }
                }
                at(path + ".", () -> modules.createModule(organizationId, actor, new NewModule(module.apiKey(),
                        module.labelSingular(), module.labelPlural(), module.description(), module.settings(),
                        fields)));
                newModules.add(module.apiKey());
                createdModules.add(module.apiKey());
                fields.forEach(field -> createdFields.add(module.apiKey() + "." + field.apiKey()));
            }
        }

        /** Step 2: missing fields (lookups included) and missing options of existing select fields. */
        void fields(List<PackModule> pack) {
            for (int i = 0; i < pack.size(); i++) {
                PackModule module = pack.get(i);
                ModuleSchema schema = modules.find(organizationId, module.apiKey())
                        .orElseThrow(() -> ApiException.notFound("Module"));
                for (int j = 0; j < module.fields().size(); j++) {
                    NewField field = module.fields().get(j);
                    String path = "modules[" + i + "].fields[" + j + "]";
                    String key = module.apiKey() + "." + field.apiKey();
                    Optional<FieldSchema> existing = schema.field(field.apiKey());
                    if (existing.isEmpty()) {
                        NewField added = positioned(field, j);
                        at(path + ".", () -> modules.addField(organizationId, actor, module.apiKey(), added));
                        createdFields.add(key);
                    } else if (existing.get().type() != field.type()) {
                        skipped.add(new Skipped("field", key, "type_differs"));
                    } else if (!newModules.contains(module.apiKey())
                            && (field.type() == FieldType.SINGLE_SELECT || field.type() == FieldType.MULTI_SELECT)) {
                        List<FieldConfig.SelectOption> options = options(field, path);
                        at(path + ".", () -> modules.addOptions(organizationId, actor, module.apiKey(),
                                field.apiKey(), options)).forEach(value -> addedOptions.add(key + ":" + value));
                    }
                }
            }
        }

        /** Step 3: layouts of modules created now or still without a layout. */
        void layouts(List<PackModule> pack) {
            for (int i = 0; i < pack.size(); i++) {
                PackModule module = pack.get(i);
                if (module.layout() == null || module.layout().isNull()) {
                    continue;
                }
                JsonNode current = modules.layout(organizationId, module.apiKey());
                if (newModules.contains(module.apiKey()) || current.path("sections").isEmpty()) {
                    at("modules[" + i + "].", () -> modules.setLayout(organizationId, actor, module.apiKey(),
                            module.layout()));
                    layouts.add(module.apiKey());
                }
            }
        }

        /** Step 4: missing roles; existing role documents are never overwritten. */
        void roles(List<PackRole> pack) {
            for (int i = 0; i < pack.size(); i++) {
                PackRole role = pack.get(i);
                if (roles.find(organizationId, role.apiKey()).isPresent()) {
                    skipped.add(new Skipped("role", role.apiKey(), "exists"));
                    continue;
                }
                at("roles[" + i + "].", () -> roles.create(organizationId, actor, role.apiKey(), role.label(),
                        role.description(), role.permissions()));
                createdRoles.add(role.apiKey());
            }
        }

        /** Step 5: missing relationships. */
        void relationships(List<PackRelationship> pack) {
            for (int i = 0; i < pack.size(); i++) {
                PackRelationship relationship = pack.get(i);
                if (relationships.find(organizationId, relationship.apiKey()).isPresent()) {
                    skipped.add(new Skipped("relationship", relationship.apiKey(), "exists"));
                    continue;
                }
                at("relationships[" + i + "].", () -> relationships.create(organizationId, actor,
                        new RelationshipDefinition(relationship.apiKey(), relationship.label(),
                                relationship.inverseLabel(), relationship.sourceModule(), relationship.targetModule(),
                                relationship.cardinality(), relationship.onDelete())));
                createdRelationships.add(relationship.apiKey());
            }
        }

        /** Step 6: missing workflows. */
        void workflows(List<PackWorkflow> pack) {
            for (int i = 0; i < pack.size(); i++) {
                PackWorkflow workflow = pack.get(i);
                String path = "workflows[" + i + "]";
                if (modules.find(organizationId, workflow.module()).isEmpty()) {
                    throw ApiException.validation(path + ".module", "unknown_module",
                            "no module with apiKey '" + workflow.module() + "'");
                }
                if (workflows.find(organizationId, workflow.module()).isPresent()) {
                    skipped.add(new Skipped("workflow", workflow.module(), "exists"));
                    continue;
                }
                at(path + ".", () -> workflows.create(organizationId, actor, workflow.module(), workflow.field(),
                        workflow.definition()));
                createdWorkflows.add(workflow.module());
            }
        }

        private List<FieldConfig.SelectOption> options(NewField field, String path) {
            if (field.config() == null || field.config().isNull()) {
                return List.of();
            }
            try {
                return json.mapper().treeToValue(field.config(), FieldConfig.class).optionsOrEmpty();
            } catch (RuntimeException ex) {
                throw ApiException.validation(path + ".config", "invalid",
                        "config contains unknown or malformed properties");
            }
        }
    }

    /** Fields without an explicit position keep the order of the pack. */
    private static NewField positioned(NewField f, int index) {
        if (f.position() != null) {
            return f;
        }
        return new NewField(f.apiKey(), f.label(), f.description(), f.type(), f.required(), f.unique(), f.readOnly(),
                f.sensitive(), f.searchable(), f.filterable(), f.sortable(), f.defaultValue(), f.config(), index);
    }

    /** Runs a definition step, reporting its failures at the pack path of the entry. */
    private static <T> T at(String prefix, Supplier<T> step) {
        try {
            return step.get();
        } catch (ApiException ex) {
            List<FieldViolation> violations = ex.violations().stream()
                    .map(v -> new FieldViolation(prefix + v.field(), v.code(), v.message()))
                    .toList();
            String context = prefix.substring(0, prefix.length() - 1);
            throw new ApiException(ex.status(), ex.code(), context + ": " + ex.getMessage(), violations);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
