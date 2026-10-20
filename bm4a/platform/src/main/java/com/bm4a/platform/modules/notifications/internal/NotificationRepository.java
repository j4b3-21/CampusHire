package com.bm4a.platform.modules.notifications.internal;

import static com.bm4a.platform.modules.shared.persistence.SqlValues.instant;
import static com.bm4a.platform.modules.shared.persistence.SqlValues.ts;
import static com.bm4a.platform.modules.shared.persistence.SqlValues.uuid;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.bm4a.platform.modules.shared.persistence.JsonColumns;

/** Every query is constrained to one recipient ({@code user_id}); nothing reads another user's rows. */
@Repository
class NotificationRepository {

    static final int BATCH_SIZE = 1000;

    record NewNotification(UUID id, UUID organizationId, UUID userId, String type, String title, String body,
            String link, String data, Instant createdAt) {
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate batch;
    private final RowMapper<NotificationView> mapper;

    NotificationRepository(JdbcClient jdbc, JdbcTemplate batch, JsonColumns json) {
        this.jdbc = jdbc;
        this.batch = batch;
        this.mapper = (rs, n) -> {
            Instant readAt = instant(rs, "read_at");
            return new NotificationView(uuid(rs, "id"), uuid(rs, "organization_id"), rs.getString("type"),
                    rs.getString("title"), rs.getString("body"), rs.getString("link"),
                    json.readObject(rs.getString("data")), readAt != null, readAt, instant(rs, "created_at"));
        };
    }

    void insertAll(List<NewNotification> rows) {
        batch.batchUpdate("""
                INSERT INTO notification (id, organization_id, user_id, type, title, body, link, data, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, rows, BATCH_SIZE, (ps, row) -> {
            ps.setObject(1, row.id());
            ps.setObject(2, row.organizationId());
            ps.setObject(3, row.userId());
            ps.setString(4, row.type());
            ps.setString(5, row.title());
            ps.setString(6, row.body());
            ps.setString(7, row.link());
            ps.setString(8, row.data());
            ps.setObject(9, ts(row.createdAt()));
        });
    }

    List<NotificationView> page(UUID userId, boolean unreadOnly, long offset, int limit) {
        return jdbc.sql("SELECT * FROM notification WHERE user_id = :user" + unreadFilter(unreadOnly)
                + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
                .param("user", userId)
                .param("limit", limit)
                .param("offset", offset)
                .query(mapper)
                .list();
    }

    long count(UUID userId, boolean unreadOnly) {
        return jdbc.sql("SELECT count(*) FROM notification WHERE user_id = :user" + unreadFilter(unreadOnly))
                .param("user", userId)
                .query(Long.class)
                .single();
    }

    /** Unread counts of the given users; users without unread notifications are absent. */
    Map<UUID, Long> unreadCounts(Collection<UUID> userIds) {
        Map<UUID, Long> counts = new HashMap<>();
        if (userIds.isEmpty()) {
            return counts;
        }
        jdbc.sql("""
                SELECT user_id, count(*) AS unread FROM notification
                 WHERE user_id IN (:users) AND read_at IS NULL
                 GROUP BY user_id
                """)
                .param("users", userIds)
                .query((rs, n) -> Map.entry(uuid(rs, "user_id"), rs.getLong("unread")))
                .list()
                .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        return counts;
    }

    private static String unreadFilter(boolean unreadOnly) {
        return unreadOnly ? " AND read_at IS NULL" : "";
    }

    /** Marks one of the user's notifications read (keeping an earlier read time); empty if it is not theirs. */
    Optional<NotificationView> markRead(UUID userId, UUID id, Instant now) {
        return jdbc.sql("""
                UPDATE notification SET read_at = COALESCE(read_at, :now)
                 WHERE id = :id AND user_id = :user
                RETURNING *
                """)
                .param("id", id)
                .param("user", userId)
                .param("now", ts(now))
                .query(mapper)
                .optional();
    }

    int markAllRead(UUID userId, Instant now) {
        return jdbc.sql("UPDATE notification SET read_at = :now WHERE user_id = :user AND read_at IS NULL")
                .param("user", userId)
                .param("now", ts(now))
                .update();
    }
}
