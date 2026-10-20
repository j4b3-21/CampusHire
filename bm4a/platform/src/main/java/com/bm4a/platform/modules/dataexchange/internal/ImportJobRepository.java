package com.bm4a.platform.modules.dataexchange.internal;

import static com.bm4a.platform.modules.shared.persistence.SqlValues.instant;
import static com.bm4a.platform.modules.shared.persistence.SqlValues.ts;
import static com.bm4a.platform.modules.shared.persistence.SqlValues.uuid;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.bm4a.platform.modules.shared.persistence.JsonColumns;

import tools.jackson.databind.JsonNode;

/**
 * Every API statement is scoped by {@code organization_id}; worker statements address jobs by ID and only touch
 * {@code RUNNING} jobs, so a job cancelled meanwhile is never overwritten.
 */
@Repository
class ImportJobRepository {

    private final JdbcClient jdbc;
    private final JsonColumns json;

    ImportJobRepository(JdbcClient jdbc, JsonColumns json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** @return {@code false} when the module already has a queued or running job */
    boolean insert(ImportJob job) {
        return jdbc.sql("""
                INSERT INTO import_job (id, organization_id, module_id, module_key, file_id, file_name, format, mode,
                                        match_field, mapping, dry_run, status, created_by, created_at)
                VALUES (:id, :org, :module, :moduleKey, :fileId, :fileName, :format, :mode, :matchField,
                        CAST(:mapping AS jsonb), :dryRun, 'QUEUED', :createdBy, :createdAt)
                ON CONFLICT (organization_id, module_id) WHERE status IN ('QUEUED', 'RUNNING') DO NOTHING
                """)
                .param("id", job.id())
                .param("org", job.organizationId())
                .param("module", job.moduleId())
                .param("moduleKey", job.moduleKey())
                .param("fileId", job.fileId())
                .param("fileName", job.fileName())
                .param("format", job.format().name())
                .param("mode", job.mode().name())
                .param("matchField", job.matchField())
                .param("mapping", json.writeValue(job.mapping()))
                .param("dryRun", job.dryRun())
                .param("createdBy", job.createdBy())
                .param("createdAt", ts(job.createdAt()))
                .update() == 1;
    }

    boolean hasActive(UUID organizationId, UUID moduleId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM import_job WHERE organization_id = :org AND module_id = :module
                                  AND status IN ('QUEUED', 'RUNNING'))
                """)
                .param("org", organizationId)
                .param("module", moduleId)
                .query(Boolean.class)
                .single();
    }

    Optional<ImportJob> find(UUID organizationId, UUID id) {
        return jdbc.sql("SELECT * FROM import_job WHERE id = :id AND organization_id = :org")
                .param("id", id)
                .param("org", organizationId)
                .query(this::map)
                .optional();
    }

    /** @param createdBy only jobs of this user, or {@code null} for all */
    List<ImportJob> list(UUID organizationId, UUID moduleId, UUID createdBy, long offset, int limit) {
        return jdbc.sql("SELECT * FROM import_job WHERE organization_id = :org AND module_id = :module"
                        + (createdBy == null ? "" : " AND created_by = :createdBy")
                        + " ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset")
                .param("org", organizationId)
                .param("module", moduleId)
                .param("createdBy", createdBy)
                .param("limit", limit)
                .param("offset", offset)
                .query(this::map)
                .list();
    }

    long count(UUID organizationId, UUID moduleId, UUID createdBy) {
        return jdbc.sql("SELECT count(*) FROM import_job WHERE organization_id = :org AND module_id = :module"
                        + (createdBy == null ? "" : " AND created_by = :createdBy"))
                .param("org", organizationId)
                .param("module", moduleId)
                .param("createdBy", createdBy)
                .query(Long.class)
                .single();
    }

    /** Cancels a queued or running job. @return {@code false} when it had already finished */
    boolean cancel(UUID organizationId, UUID id, Instant now) {
        return jdbc.sql("""
                UPDATE import_job SET status = 'CANCELLED', finished_at = :now
                 WHERE id = :id AND organization_id = :org AND status IN ('QUEUED', 'RUNNING')
                """)
                .param("id", id)
                .param("org", organizationId)
                .param("now", ts(now))
                .update() == 1;
    }

    // ---- worker --------------------------------------------------------------------------------------------

    /** Atomically moves the oldest queued job to RUNNING; concurrent workers skip rows locked by each other. */
    Optional<ImportJob> claimNext(Instant now) {
        return jdbc.sql("""
                UPDATE import_job SET status = 'RUNNING', started_at = :now, heartbeat_at = :now
                 WHERE id = (SELECT id FROM import_job WHERE status = 'QUEUED'
                              ORDER BY created_at, id FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING *
                """)
                .param("now", ts(now))
                .query(this::map)
                .optional();
    }

    /** Records progress and refreshes the heartbeat. @return {@code false} when the job is no longer running */
    boolean progress(UUID id, Integer totalRows, int processed, int created, int updated, int failed, Instant now) {
        return jdbc.sql("""
                UPDATE import_job SET total_rows = :total, processed_rows = :processed, created_rows = :created,
                                      updated_rows = :updated, failed_rows = :failed, heartbeat_at = :now
                 WHERE id = :id AND status = 'RUNNING'
                """)
                .param("id", id)
                .param("total", totalRows)
                .param("processed", processed)
                .param("created", created)
                .param("updated", updated)
                .param("failed", failed)
                .param("now", ts(now))
                .update() == 1;
    }

    /**
     * Final counters and error report. A running job gets {@code status}; a job cancelled while running keeps
     * {@code CANCELLED} and receives the counters of the batches that completed.
     */
    void finish(UUID id, ImportStatus status, int processed, int created, int updated, int failed, UUID errorFileId,
            JsonNode errorSample, String errorMessage, Instant now) {
        jdbc.sql("""
                UPDATE import_job
                   SET status = CASE WHEN status = 'RUNNING' THEN :status ELSE status END,
                       processed_rows = :processed, created_rows = :created, updated_rows = :updated,
                       failed_rows = :failed, error_file_id = :errorFile, error_sample = CAST(:sample AS jsonb),
                       error_message = :message, finished_at = :now, heartbeat_at = :now
                 WHERE id = :id AND status IN ('RUNNING', 'CANCELLED')
                """)
                .param("id", id)
                .param("status", status.name())
                .param("processed", processed)
                .param("created", created)
                .param("updated", updated)
                .param("failed", failed)
                .param("errorFile", errorFileId)
                .param("sample", json.write(errorSample))
                .param("message", truncate(errorMessage))
                .param("now", ts(now))
                .update();
    }

    /** A job that could not run at all. */
    void fail(UUID id, String errorMessage, Instant now) {
        jdbc.sql("""
                UPDATE import_job SET status = 'FAILED', error_message = :message, finished_at = :now, heartbeat_at = :now
                 WHERE id = :id AND status = 'RUNNING'
                """)
                .param("id", id)
                .param("message", truncate(errorMessage))
                .param("now", ts(now))
                .update();
    }

    /**
     * Running jobs whose worker stopped heartbeating: dry runs wrote nothing and are queued again; real imports may
     * have committed batches, so they fail (re-running in UPSERT mode is safe).
     */
    int recoverStale(Instant staleBefore, Instant now) {
        int requeued = jdbc.sql("""
                UPDATE import_job SET status = 'QUEUED', started_at = NULL, heartbeat_at = NULL, total_rows = NULL,
                                      processed_rows = 0, created_rows = 0, updated_rows = 0, failed_rows = 0
                 WHERE status = 'RUNNING' AND dry_run AND heartbeat_at < :staleBefore
                """)
                .param("staleBefore", ts(staleBefore))
                .update();
        int failed = jdbc.sql("""
                UPDATE import_job SET status = 'FAILED', finished_at = :now,
                       error_message = 'The import was interrupted after ' || processed_rows
                           || ' rows; rows of completed batches were saved. Import the file again in UPSERT mode.'
                 WHERE status = 'RUNNING' AND NOT dry_run AND heartbeat_at < :staleBefore
                """)
                .param("staleBefore", ts(staleBefore))
                .param("now", ts(now))
                .update();
        return requeued + failed;
    }

    private ImportJob map(ResultSet rs, int n) throws SQLException {
        Map<String, String> mapping = new LinkedHashMap<>();
        json.readObject(rs.getString("mapping")).properties()
                .forEach(entry -> mapping.put(entry.getKey(), entry.getValue().asString()));
        int total = rs.getInt("total_rows");
        Integer totalRows = rs.wasNull() ? null : total;
        return new ImportJob(uuid(rs, "id"), uuid(rs, "organization_id"), uuid(rs, "module_id"),
                rs.getString("module_key"), uuid(rs, "file_id"), rs.getString("file_name"),
                SpreadsheetFormat.valueOf(rs.getString("format")), ImportMode.valueOf(rs.getString("mode")),
                rs.getString("match_field"), mapping, rs.getBoolean("dry_run"),
                ImportStatus.valueOf(rs.getString("status")), totalRows, rs.getInt("processed_rows"),
                rs.getInt("created_rows"), rs.getInt("updated_rows"), rs.getInt("failed_rows"),
                uuid(rs, "error_file_id"), json.read(rs.getString("error_sample")), rs.getString("error_message"),
                uuid(rs, "created_by"), instant(rs, "created_at"), instant(rs, "started_at"),
                instant(rs, "finished_at"), instant(rs, "heartbeat_at"));
    }

    private static String truncate(String message) {
        return message == null || message.length() <= 1000 ? message : message.substring(0, 997) + "...";
    }
}
