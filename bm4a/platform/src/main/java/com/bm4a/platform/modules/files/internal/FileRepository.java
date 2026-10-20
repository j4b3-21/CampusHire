package com.bm4a.platform.modules.files.internal;

import static com.bm4a.platform.modules.shared.persistence.SqlValues.instant;
import static com.bm4a.platform.modules.shared.persistence.SqlValues.ts;
import static com.bm4a.platform.modules.shared.persistence.SqlValues.uuid;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.bm4a.platform.modules.files.StoredFileView;

/** Every statement is scoped by {@code organization_id}. */
@Repository
class FileRepository {

    record FileRow(StoredFileView view, String storageKey) {
    }

    private static final RowMapper<FileRow> MAPPER = (rs, n) -> new FileRow(new StoredFileView(uuid(rs, "id"),
            uuid(rs, "organization_id"), rs.getString("original_name"), rs.getString("content_type"),
            rs.getLong("size_bytes"), rs.getString("sha256"), instant(rs, "created_at"), uuid(rs, "created_by")),
            rs.getString("storage_key"));

    private final JdbcClient jdbc;

    FileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(FileRow row) {
        StoredFileView file = row.view();
        jdbc.sql("""
                INSERT INTO stored_file (id, organization_id, storage_key, original_name, content_type, size_bytes,
                                         sha256, status, created_at, created_by)
                VALUES (:id, :org, :key, :name, :type, :size, :sha256, 'ACTIVE', :createdAt, :createdBy)
                """)
                .param("id", file.id())
                .param("org", file.organizationId())
                .param("key", row.storageKey())
                .param("name", file.name())
                .param("type", file.contentType())
                .param("size", file.sizeBytes())
                .param("sha256", file.sha256())
                .param("createdAt", ts(file.createdAt()))
                .param("createdBy", file.createdBy())
                .update();
    }

    Optional<FileRow> findActive(UUID organizationId, UUID id) {
        return jdbc.sql("SELECT * FROM stored_file WHERE id = :id AND organization_id = :org AND status = 'ACTIVE'")
                .param("id", id)
                .param("org", organizationId)
                .query(MAPPER)
                .optional();
    }
}
