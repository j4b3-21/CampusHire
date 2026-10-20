package com.bm4a.platform.modules.relationships.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.bm4a.platform.modules.access.FieldPolicy;
import com.bm4a.platform.modules.access.ModuleAccess;
import com.bm4a.platform.modules.access.ModuleGrant;
import com.bm4a.platform.modules.access.RecordAction;
import com.bm4a.platform.modules.audit.AuditEntry;
import com.bm4a.platform.modules.audit.AuditLog;
import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.metadata.ModuleSchemas;
import com.bm4a.platform.modules.records.RecordDeletionGuard;
import com.bm4a.platform.modules.records.RecordReader;
import com.bm4a.platform.modules.records.RecordScopes;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.relationships.internal.RelationshipRow.Cardinality;
import com.bm4a.platform.modules.relationships.internal.RelationshipRow.OnDelete;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.error.FieldViolation;
import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.tenancy.TenantContext;

/**
 * Relationship definitions and links. Both ends of a link must be active records of the relationship's modules in the
 * caller's tenant; cardinality is enforced by unique indexes. Linking and unlinking require update access to the
 * source record and read access to the target record (ADR-004 module permissions and record scopes). Also the {@link RecordDeletionGuard} applying each
 * relationship's {@link OnDelete} policy.
 */
@Service
public class RelationshipService implements RecordDeletionGuard {

    private static final Pattern API_KEY = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    private final RelationshipRepository repository;
    private final ModuleSchemas schemas;
    private final RecordReader records;
    private final ModuleAccess access;
    private final AuditLog audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    RelationshipService(RelationshipRepository repository, ModuleSchemas schemas, RecordReader records,
            ModuleAccess access, AuditLog audit, TransactionTemplate transactions, Clock clock) {
        this.repository = repository;
        this.schemas = schemas;
        this.records = records;
        this.access = access;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    public record RelationshipInput(String apiKey, String label, String inverseLabel, String sourceModule,
            String targetModule, Cardinality cardinality, OnDelete onDelete) {
    }

    /** A relationship with its module keys resolved for display. */
    public record RelationshipView(RelationshipRow relationship, String sourceModule, String targetModule) {
    }

    /** A link together with the record on the other end and the field policy for showing it. */
    public record RelatedRecord(LinkRow link, ModuleSchema module, RecordView record, FieldPolicy fields) {
    }

    @Transactional
    public RelationshipView create(TenantContext tenant, RelationshipInput in) {
        List<FieldViolation> violations = new ArrayList<>();
        if (in.apiKey() == null || !API_KEY.matcher(in.apiKey()).matches()) {
            violations.add(new FieldViolation("apiKey", "format", "apiKey must match ^[a-z][a-z0-9_]{0,62}$"));
        }
        if (in.label() == null || in.label().isBlank() || in.label().length() > 200) {
            violations.add(new FieldViolation("label", "size", "label must be 1-200 characters"));
        }
        if (in.inverseLabel() != null && (in.inverseLabel().isBlank() || in.inverseLabel().length() > 200)) {
            violations.add(new FieldViolation("inverseLabel", "size", "inverseLabel must be 1-200 characters"));
        }
        if (in.cardinality() == null) {
            violations.add(new FieldViolation("cardinality", "required", "cardinality is required"));
        }
        ModuleSchema source = activeModule(tenant, in.sourceModule(), "sourceModule", violations);
        ModuleSchema target = activeModule(tenant, in.targetModule(), "targetModule", violations);
        if (!violations.isEmpty()) {
            throw ApiException.validation(violations);
        }
        Instant now = SqlValues.now(clock);
        RelationshipRow row = new RelationshipRow(UUID.randomUUID(), tenant.organizationId(), in.apiKey(),
                in.label().strip(), in.inverseLabel(), source.id(), target.id(), in.cardinality(),
                in.onDelete() == null ? OnDelete.RESTRICT : in.onDelete(), "ACTIVE", 1, now, tenant.userId(), now,
                tenant.userId());
        try {
            repository.insert(row);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("relationship_exists", "A relationship '" + in.apiKey() + "' already exists");
        }
        audit.record(AuditEntry.of(tenant.organizationId(), tenant.userId(), "relationship.created", "relationship",
                row.id()).with("apiKey", row.apiKey()).with("cardinality", row.cardinality().name())
                .with("source", source.apiKey()).with("target", target.apiKey()));
        return new RelationshipView(row, source.apiKey(), target.apiKey());
    }

    @Transactional(readOnly = true)
    public List<RelationshipView> list(TenantContext tenant) {
        return repository.list(tenant.organizationId()).stream().map(row -> view(tenant, row)).toList();
    }

    @Transactional(readOnly = true)
    public RelationshipView get(TenantContext tenant, String apiKey) {
        return view(tenant, relationship(tenant, apiKey, false));
    }

    /** Archived relationships keep their links readable but accept no new links. */
    @Transactional
    public RelationshipView archive(TenantContext tenant, String apiKey, long expectedVersion) {
        RelationshipRow current = relationship(tenant, apiKey, true);
        if (current.version() != expectedVersion) {
            throw ApiException.versionConflict(current.version());
        }
        if (current.active()) {
            repository.updateStatus(tenant.organizationId(), current.id(), "ARCHIVED", current.version() + 1,
                    tenant.userId(), SqlValues.now(clock));
            audit.record(AuditEntry.of(tenant.organizationId(), tenant.userId(), "relationship.archived",
                    "relationship", current.id()).with("apiKey", apiKey));
        }
        return get(tenant, apiKey);
    }

    /**
     * Module access is resolved before the write transaction, so that access-denied audit events are not rolled back
     * with it.
     */
    public LinkRow link(TenantContext tenant, String apiKey, UUID sourceRecordId, UUID targetRecordId) {
        RelationshipRow relationship = relationship(tenant, apiKey, false);
        if (!relationship.active()) {
            throw ApiException.conflict("relationship_archived", "Relationship is archived");
        }
        ModuleGrant source = linkGrant(tenant, relationship.sourceModuleId(), RecordAction.UPDATE);
        ModuleGrant target = linkGrant(tenant, relationship.targetModuleId(), RecordAction.READ);
        return transactions.execute(status -> {
            List<FieldViolation> violations = new ArrayList<>();
            if (sourceRecordId == null || records.findActive(source, sourceRecordId).isEmpty()) {
                violations.add(new FieldViolation("sourceRecordId", "invalid_reference",
                        "no active record of the source module with this ID"));
            }
            if (targetRecordId == null || records.findActive(target, targetRecordId).isEmpty()) {
                violations.add(new FieldViolation("targetRecordId", "invalid_reference",
                        "no active record of the target module with this ID"));
            }
            if (!violations.isEmpty()) {
                throw ApiException.validation(violations);
            }
            LinkRow link = new LinkRow(UUID.randomUUID(), tenant.organizationId(), relationship.id(), sourceRecordId,
                    targetRecordId, SqlValues.now(clock), tenant.userId());
            if (!repository.insertLink(link, relationship.cardinality())) {
                if (repository.linkExists(tenant.organizationId(), relationship.id(), sourceRecordId,
                        targetRecordId)) {
                    throw ApiException.conflict("link_exists", "These records are already linked");
                }
                throw ApiException.conflict("cardinality_violation",
                        "Link would violate the " + relationship.cardinality() + " cardinality of '" + apiKey + "'");
            }
            audit.record(AuditEntry.of(tenant.organizationId(), tenant.userId(), "relationship.linked", "record_link",
                    link.id()).with("relationship", apiKey).with("source", sourceRecordId.toString())
                    .with("target", targetRecordId.toString()));
            return link;
        });
    }

    /** Like {@link #link}, module access is resolved before the write transaction. */
    public void unlink(TenantContext tenant, String apiKey, UUID linkId) {
        RelationshipRow relationship = relationship(tenant, apiKey, false);
        ModuleGrant source = linkGrant(tenant, relationship.sourceModuleId(), RecordAction.UPDATE);
        ModuleGrant target = linkGrant(tenant, relationship.targetModuleId(), RecordAction.READ);
        transactions.executeWithoutResult(status -> {
            LinkRow link = repository.findLink(tenant.organizationId(), relationship.id(), linkId)
                    .orElseThrow(() -> ApiException.notFound("Link"));
            if (records.findActive(source, link.sourceRecordId()).isEmpty()
                    || records.findActive(target, link.targetRecordId()).isEmpty()) {
                throw ApiException.notFound("Link");
            }
            repository.deleteLink(tenant.organizationId(), link.id());
            audit.record(AuditEntry.of(tenant.organizationId(), tenant.userId(), "relationship.unlinked",
                    "record_link", link.id()).with("relationship", apiKey)
                    .with("source", link.sourceRecordId().toString()).with("target", link.targetRecordId().toString()));
        });
    }

    /**
     * Records related to {@code recordId} through a relationship, as seen by the holder of {@code sourceGrant} (read
     * access to the record's module). The direction follows from the module's side of the relationship; for
     * self-relationships {@code incoming} selects the target side. Reading the other side requires read access to its
     * module: only records within that grant's scope are listed, and its field policy applies. Not transactional,
     * so that an access-denied audit event for the other module is recorded.
     */
    public PageResponse<RelatedRecord> related(ModuleGrant sourceGrant, UUID recordId, String apiKey,
            boolean incoming, PageQuery page) {
        TenantContext tenant = sourceGrant.tenant();
        ModuleSchema module = sourceGrant.module();
        records.findActive(sourceGrant, recordId).orElseThrow(() -> ApiException.notFound("Record"));
        RelationshipRow relationship = relationship(tenant, apiKey, false);
        boolean isSource = relationship.sourceModuleId().equals(module.id());
        boolean isTarget = relationship.targetModuleId().equals(module.id());
        if (!isSource && !isTarget) {
            throw ApiException.badRequest("module_not_in_relationship",
                    "Module '" + module.apiKey() + "' is not part of relationship '" + apiKey + "'");
        }
        boolean outgoing = isSource && !(isTarget && incoming);
        UUID otherModuleId = outgoing ? relationship.targetModuleId() : relationship.sourceModuleId();
        ModuleGrant otherGrant = linkGrant(tenant, otherModuleId, RecordAction.READ);
        RecordScopes.Predicate scope = RecordScopes.predicate(otherGrant.scope(), otherGrant.module(), "r", "s");
        List<LinkRow> links = repository.links(tenant.organizationId(), relationship.id(), recordId, outgoing, scope,
                page.offset(), page.size());
        Map<UUID, RecordView> others = records.findActive(tenant.organizationId(),
                links.stream().map(link -> outgoing ? link.targetRecordId() : link.sourceRecordId()).toList())
                .stream().collect(Collectors.toMap(RecordView::id, Function.identity()));
        List<RelatedRecord> items = links.stream()
                .map(link -> new RelatedRecord(link, otherGrant.module(), others.get(outgoing ? link.targetRecordId()
                        : link.sourceRecordId()), otherGrant.fields()))
                .filter(item -> item.record() != null)
                .toList();
        return PageResponse.of(items, page,
                repository.countLinks(tenant.organizationId(), relationship.id(), recordId, outgoing, scope));
    }

    @Override
    public void beforeDelete(TenantContext tenant, ModuleSchema module, RecordView record) {
        List<LinkRow> links = repository.linksOfRecord(tenant.organizationId(), record.id());
        Map<UUID, RelationshipRow> relationships = links.stream().map(LinkRow::relationshipId).distinct()
                .map(id -> repository.findById(tenant.organizationId(), id).orElseThrow())
                .collect(Collectors.toMap(RelationshipRow::id, Function.identity()));
        for (LinkRow link : links) {
            RelationshipRow relationship = relationships.get(link.relationshipId());
            if (relationship.onDelete() == OnDelete.RESTRICT) {
                throw ApiException.conflict("record_linked", "Record is linked through relationship '"
                        + relationship.apiKey() + "'; remove the links first");
            }
        }
        for (LinkRow link : links) {
            repository.deleteLink(tenant.organizationId(), link.id());
        }
        if (!links.isEmpty()) {
            audit.record(AuditEntry.of(tenant.organizationId(), tenant.userId(), "relationship.links_detached",
                    "record", record.id()).with("links", links.size()));
        }
    }

    /** Module access for one side of a relationship, checked for the caller of {@code tenant}. */
    private ModuleGrant linkGrant(TenantContext tenant, UUID moduleId, RecordAction action) {
        ModuleSchema module = schemas.findById(tenant.organizationId(), moduleId)
                .orElseThrow(() -> ApiException.notFound("Module"));
        return access.require(tenant, module.apiKey(), action);
    }

    private RelationshipRow relationship(TenantContext tenant, String apiKey, boolean forUpdate) {
        return repository.find(tenant.organizationId(), apiKey, forUpdate)
                .orElseThrow(() -> ApiException.notFound("Relationship"));
    }

    private ModuleSchema activeModule(TenantContext tenant, String key, String field, List<FieldViolation> violations) {
        ModuleSchema module = key == null ? null
                : schemas.find(tenant.organizationId(), key).filter(ModuleSchema::active).orElse(null);
        if (module == null) {
            violations.add(new FieldViolation(field, "unknown_module", "no active module with this apiKey"));
        }
        return module;
    }

    private RelationshipView view(TenantContext tenant, RelationshipRow row) {
        String source = schemas.findById(tenant.organizationId(), row.sourceModuleId()).map(ModuleSchema::apiKey)
                .orElse(null);
        String target = schemas.findById(tenant.organizationId(), row.targetModuleId()).map(ModuleSchema::apiKey)
                .orElse(null);
        return new RelationshipView(row, source, target);
    }
}
