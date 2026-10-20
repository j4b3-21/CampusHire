package com.bm4a.platform.modules.relationships.internal;

import static com.bm4a.platform.modules.shared.persistence.SqlValues.instant;
import static com.bm4a.platform.modules.shared.persistence.SqlValues.ts;
import static com.bm4a.platform.modules.shared.persistence.SqlValues.uuid;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.bm4a.platform.modules.records.RecordScopes;
import com.bm4a.platform.modules.relationships.internal.RelationshipRow.Cardinality;
import com.bm4a.platform.modules.relationships.internal.RelationshipRow.OnDelete;

/**
 * Every statement is scoped by {@code organization_id}. Related-record listings join the record engine's
 * {@code record} table (read only) so that record scopes and soft deletion apply inside the paged query.
 */
@Repository
class RelationshipRepository {

    private static final RowMapper<RelationshipRow> RELATIONSHIP = (rs, n) -> new RelationshipRow(uuid(rs, "id"),
            uuid(rs, "organization_id"), rs.getString("api_key"), rs.getString("label"), rs.getString("inverse_label"),
            uuid(rs, "source_module_id"), uuid(rs, "target_module_id"), Cardinality.valueOf(rs.getString("cardinality")),
            OnDelete.valueOf(rs.getString("on_delete")), rs.getString("status"), rs.getLong("version"),
            instant(rs, "created_at"), uuid(rs, "created_by"), instant(rs, "updated_at"), uuid(rs, "updated_by"));

    private static final RowMapper<LinkRow> LINK = (rs, n) -> new LinkRow(uuid(rs, "id"), uuid(rs, "organization_id"),
            uuid(rs, "relationship_id"), uuid(rs, "source_record_id"), uuid(rs, "target_record_id"),
            instant(rs, "created_at"), uuid(rs, "created_by"));

    private final JdbcClient jdbc;

    RelationshipRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(RelationshipRow r) {
        jdbc.sql("""
                INSERT INTO relationship_definition (id, organization_id, api_key, label, inverse_label, source_module_id,
                       target_module_id, cardinality, on_delete, status, version, created_at, created_by, updated_at,
                       updated_by)
                VALUES (:id, :org, :apiKey, :label, :inverseLabel, :source, :target, :cardinality, :onDelete, :status,
                       :version, :createdAt, :createdBy, :updatedAt, :updatedBy)
                """)
                .param("id", r.id())
                .param("org", r.organizationId())
                .param("apiKey", r.apiKey())
                .param("label", r.label())
                .param("inverseLabel", r.inverseLabel())
                .param("source", r.sourceModuleId())
                .param("target", r.targetModuleId())
                .param("cardinality", r.cardinality().name())
                .param("onDelete", r.onDelete().name())
                .param("status", r.status())
                .param("version", r.version())
                .param("createdAt", ts(r.createdAt()))
                .param("createdBy", r.createdBy())
                .param("updatedAt", ts(r.updatedAt()))
                .param("updatedBy", r.updatedBy())
                .update();
    }

    void updateStatus(UUID organizationId, UUID id, String status, long version, UUID actor, Instant now) {
        jdbc.sql("""
                UPDATE relationship_definition SET status = :status, version = :version, updated_at = :now,
                       updated_by = :actor
                 WHERE id = :id AND organization_id = :org
                """)
                .param("id", id)
                .param("org", organizationId)
                .param("status", status)
                .param("version", version)
                .param("now", ts(now))
                .param("actor", actor)
                .update();
    }

    Optional<RelationshipRow> find(UUID organizationId, String apiKey, boolean forUpdate) {
        return jdbc.sql("SELECT * FROM relationship_definition WHERE organization_id = :org AND api_key = :key"
                        + (forUpdate ? " FOR UPDATE" : ""))
                .param("org", organizationId)
                .param("key", apiKey)
                .query(RELATIONSHIP)
                .optional();
    }

    List<RelationshipRow> list(UUID organizationId) {
        return jdbc.sql("SELECT * FROM relationship_definition WHERE organization_id = :org ORDER BY api_key")
                .param("org", organizationId)
                .query(RELATIONSHIP)
                .list();
    }

    /** @return {@code false} if any uniqueness (duplicate link or cardinality) prevented the insert */
    boolean insertLink(LinkRow link, Cardinality cardinality) {
        return jdbc.sql("""
                INSERT INTO record_link (id, organization_id, relationship_id, cardinality, source_record_id,
                                         target_record_id, created_at, created_by)
                VALUES (:id, :org, :relationship, :cardinality, :source, :target, :createdAt, :createdBy)
                ON CONFLICT DO NOTHING
                """)
                .param("id", link.id())
                .param("org", link.organizationId())
                .param("relationship", link.relationshipId())
                .param("cardinality", cardinality.name())
                .param("source", link.sourceRecordId())
                .param("target", link.targetRecordId())
                .param("createdAt", ts(link.createdAt()))
                .param("createdBy", link.createdBy())
                .update() == 1;
    }

    boolean linkExists(UUID organizationId, UUID relationshipId, UUID source, UUID target) {
        return jdbc.sql("""
                SELECT count(*) FROM record_link WHERE organization_id = :org AND relationship_id = :rel
                   AND source_record_id = :source AND target_record_id = :target
                """)
                .param("org", organizationId)
                .param("rel", relationshipId)
                .param("source", source)
                .param("target", target)
                .query(Long.class).single() > 0;
    }

    Optional<LinkRow> findLink(UUID organizationId, UUID relationshipId, UUID linkId) {
        return jdbc.sql("SELECT * FROM record_link WHERE organization_id = :org AND relationship_id = :rel AND id = :id")
                .param("org", organizationId)
                .param("rel", relationshipId)
                .param("id", linkId)
                .query(LINK)
                .optional();
    }

    void deleteLink(UUID organizationId, UUID linkId) {
        jdbc.sql("DELETE FROM record_link WHERE organization_id = :org AND id = :id")
                .param("org", organizationId)
                .param("id", linkId)
                .update();
    }

    /**
     * Links of one record within one relationship, from the source ({@code outgoing}) or target side, whose other end is
     * an active record within {@code scope} (a predicate over the {@code record} alias {@code r}).
     */
    List<LinkRow> links(UUID organizationId, UUID relationshipId, UUID recordId, boolean outgoing,
            RecordScopes.Predicate scope, long offset, int limit) {
        return jdbc.sql("SELECT l.* FROM record_link l JOIN record r ON r.organization_id = l.organization_id AND r.id = "
                        + (outgoing ? "l.target_record_id" : "l.source_record_id")
                        + " WHERE l.organization_id = :org AND l.relationship_id = :rel AND "
                        + (outgoing ? "l.source_record_id" : "l.target_record_id")
                        + " = :record AND r.deleted_at IS NULL AND " + scope.sql()
                        + " ORDER BY l.created_at, l.id LIMIT :limit OFFSET :offset")
                .params(scope.params())
                .param("org", organizationId)
                .param("rel", relationshipId)
                .param("record", recordId)
                .param("limit", limit)
                .param("offset", offset)
                .query(LINK)
                .list();
    }

    long countLinks(UUID organizationId, UUID relationshipId, UUID recordId, boolean outgoing,
            RecordScopes.Predicate scope) {
        return jdbc.sql("SELECT count(*) FROM record_link l JOIN record r ON r.organization_id = l.organization_id"
                        + " AND r.id = " + (outgoing ? "l.target_record_id" : "l.source_record_id")
                        + " WHERE l.organization_id = :org AND l.relationship_id = :rel AND "
                        + (outgoing ? "l.source_record_id" : "l.target_record_id")
                        + " = :record AND r.deleted_at IS NULL AND " + scope.sql())
                .params(scope.params())
                .param("org", organizationId)
                .param("rel", relationshipId)
                .param("record", recordId)
                .query(Long.class).single();
    }

    /** All links touching a record, on either side, across relationships. */
    List<LinkRow> linksOfRecord(UUID organizationId, UUID recordId) {
        return jdbc.sql("""
                SELECT * FROM record_link WHERE organization_id = :org
                   AND (source_record_id = :record OR target_record_id = :record)
                """)
                .param("org", organizationId)
                .param("record", recordId)
                .query(LINK)
                .list();
    }

    Optional<RelationshipRow> findById(UUID organizationId, UUID id) {
        return jdbc.sql("SELECT * FROM relationship_definition WHERE organization_id = :org AND id = :id")
                .param("org", organizationId)
                .param("id", id)
                .query(RELATIONSHIP)
                .optional();
    }
}
