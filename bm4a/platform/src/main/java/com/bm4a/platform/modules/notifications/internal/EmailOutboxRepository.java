package com.bm4a.platform.modules.notifications.internal;

import static com.bm4a.platform.modules.shared.persistence.SqlValues.instant;
import static com.bm4a.platform.modules.shared.persistence.SqlValues.ts;
import static com.bm4a.platform.modules.shared.persistence.SqlValues.uuid;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class EmailOutboxRepository {

    static final String PENDING = "PENDING";
    static final String SENDING = "SENDING";
    static final String SENT = "SENT";
    static final String FAILED = "FAILED";

    record NewEmail(UUID id, UUID organizationId, String to, String subject, String html, String text,
            Instant createdAt) {
    }

    record OutboxRow(UUID id, UUID organizationId, String to, String subject, String html, String text, String status,
            int attempts, Instant nextAttemptAt, String lastError, Instant createdAt, Instant sentAt) {
    }

    private static final RowMapper<OutboxRow> ROW = (rs, n) -> new OutboxRow(uuid(rs, "id"),
            uuid(rs, "organization_id"), rs.getString("to_address"), rs.getString("subject"),
            rs.getString("html_body"), rs.getString("text_body"), rs.getString("status"), rs.getInt("attempts"),
            instant(rs, "next_attempt_at"), rs.getString("last_error"), instant(rs, "created_at"),
            instant(rs, "sent_at"));

    private final JdbcClient jdbc;
    private final JdbcTemplate batch;

    EmailOutboxRepository(JdbcClient jdbc, JdbcTemplate batch) {
        this.jdbc = jdbc;
        this.batch = batch;
    }

    void insertAll(List<NewEmail> rows) {
        batch.batchUpdate("""
                INSERT INTO email_outbox (id, organization_id, to_address, subject, html_body, text_body, status,
                       attempts, next_attempt_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
                """, rows, NotificationRepository.BATCH_SIZE, (ps, row) -> {
            ps.setObject(1, row.id());
            ps.setObject(2, row.organizationId());
            ps.setString(3, row.to());
            ps.setString(4, row.subject());
            ps.setString(5, row.html());
            ps.setString(6, row.text());
            ps.setObject(7, ts(row.createdAt()));
            ps.setObject(8, ts(row.createdAt()));
        });
    }

    /**
     * Claims due rows for this worker: {@code SKIP LOCKED} lets replicas claim disjoint batches. Claimed rows become
     * {@code SENDING} with a lease ({@code next_attempt_at = leaseUntil}); a row whose lease expires (the worker died)
     * is claimed again.
     */
    List<OutboxRow> claim(Instant now, Instant leaseUntil, int limit, int maxAttempts) {
        return jdbc.sql("""
                WITH due AS (
                    SELECT id FROM email_outbox
                     WHERE status IN ('PENDING', 'SENDING') AND next_attempt_at <= :now AND attempts < :maxAttempts
                     ORDER BY next_attempt_at
                     LIMIT :limit
                       FOR UPDATE SKIP LOCKED)
                UPDATE email_outbox o
                   SET status = 'SENDING', attempts = o.attempts + 1, next_attempt_at = :leaseUntil
                  FROM due
                 WHERE o.id = due.id
                RETURNING o.*
                """)
                .param("now", ts(now))
                .param("leaseUntil", ts(leaseUntil))
                .param("limit", limit)
                .param("maxAttempts", maxAttempts)
                .query(ROW)
                .list();
    }

    /** Rows whose last attempt was interrupted after the final claim: the outcome is unknown, so they fail. */
    int failAbandoned(Instant now, int maxAttempts) {
        return jdbc.sql("""
                UPDATE email_outbox
                   SET status = 'FAILED', last_error = 'Delivery was interrupted on the last attempt'
                 WHERE status = 'SENDING' AND next_attempt_at <= :now AND attempts >= :maxAttempts
                """)
                .param("now", ts(now))
                .param("maxAttempts", maxAttempts)
                .update();
    }

    /** Sent rows keep recipient and subject; bodies (which may hold one-time links) are removed. */
    void markSent(Collection<UUID> ids, Instant now) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("""
                UPDATE email_outbox
                   SET status = 'SENT', sent_at = :now, html_body = NULL, text_body = NULL, last_error = NULL
                 WHERE id IN (:ids) AND status = 'SENDING'
                """)
                .param("ids", ids)
                .param("now", ts(now))
                .update();
    }

    void markRetry(UUID id, Instant nextAttemptAt, String error) {
        jdbc.sql("""
                UPDATE email_outbox SET status = 'PENDING', next_attempt_at = :next, last_error = :error
                 WHERE id = :id AND status = 'SENDING'
                """)
                .param("id", id)
                .param("next", ts(nextAttemptAt))
                .param("error", error)
                .update();
    }

    void markFailed(UUID id, String error) {
        jdbc.sql("UPDATE email_outbox SET status = 'FAILED', last_error = :error WHERE id = :id AND status = 'SENDING'")
                .param("id", id)
                .param("error", error)
                .update();
    }
}
