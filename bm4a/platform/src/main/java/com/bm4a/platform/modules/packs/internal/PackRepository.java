package com.bm4a.platform.modules.packs.internal;

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

import com.bm4a.platform.modules.packs.InstalledPack;
import com.bm4a.platform.modules.shared.persistence.JsonColumns;

import tools.jackson.databind.JsonNode;

/** Every statement is scoped by {@code organization_id}. */
@Repository
class PackRepository {

    private static final RowMapper<InstalledPack> MAPPER = (rs, n) -> new InstalledPack(rs.getString("pack_key"),
            rs.getInt("version"), rs.getString("label"), rs.getString("definition_sha256"),
            instant(rs, "installed_at"), uuid(rs, "installed_by"));

    private final JdbcClient jdbc;
    private final JsonColumns json;

    PackRepository(JdbcClient jdbc, JsonColumns json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Serializes pack installs per organization until the end of the current transaction. */
    void lockOrganization(UUID organizationId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "bm4a.packs:" + organizationId)
                .query((rs, n) -> Boolean.TRUE)
                .single();
    }

    Optional<InstalledPack> find(UUID organizationId, String packKey) {
        return jdbc.sql("SELECT * FROM installed_pack WHERE organization_id = :org AND pack_key = :key")
                .param("org", organizationId)
                .param("key", packKey)
                .query(MAPPER)
                .optional();
    }

    List<InstalledPack> list(UUID organizationId) {
        return jdbc.sql("SELECT * FROM installed_pack WHERE organization_id = :org ORDER BY pack_key")
                .param("org", organizationId)
                .query(MAPPER)
                .list();
    }

    void upsert(UUID organizationId, InstalledPack pack) {
        jdbc.sql("""
                INSERT INTO installed_pack (organization_id, pack_key, version, label, definition_sha256, installed_at,
                                            installed_by)
                VALUES (:org, :key, :version, :label, :sha, :at, :by)
                ON CONFLICT (organization_id, pack_key)
                DO UPDATE SET version = EXCLUDED.version, label = EXCLUDED.label,
                              definition_sha256 = EXCLUDED.definition_sha256, installed_at = EXCLUDED.installed_at,
                              installed_by = EXCLUDED.installed_by
                """)
                .param("org", organizationId)
                .param("key", pack.packKey())
                .param("version", pack.version())
                .param("label", pack.label())
                .param("sha", pack.definitionSha256())
                .param("at", ts(pack.installedAt()))
                .param("by", pack.installedBy())
                .update();
    }

    void insertHistory(UUID organizationId, String packKey, Integer fromVersion, int toVersion, String status,
            String sha256, JsonNode report, Instant at, UUID by) {
        jdbc.sql("""
                INSERT INTO installed_pack_history (id, organization_id, pack_key, from_version, to_version, status,
                                                    definition_sha256, report, installed_at, installed_by)
                VALUES (:id, :org, :key, :from, :to, :status, :sha, CAST(:report AS jsonb), :at, :by)
                """)
                .param("id", UUID.randomUUID())
                .param("org", organizationId)
                .param("key", packKey)
                .param("from", fromVersion)
                .param("to", toVersion)
                .param("status", status)
                .param("sha", sha256)
                .param("report", json.write(report))
                .param("at", ts(at))
                .param("by", by)
                .update();
    }
}
