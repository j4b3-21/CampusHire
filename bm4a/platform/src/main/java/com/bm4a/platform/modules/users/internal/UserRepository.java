package com.bm4a.platform.modules.users.internal;

import static com.bm4a.platform.modules.shared.persistence.SqlValues.instant;
import static com.bm4a.platform.modules.shared.persistence.SqlValues.ts;
import static com.bm4a.platform.modules.shared.persistence.SqlValues.uuid;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.bm4a.platform.modules.shared.persistence.JsonColumns;

@Repository
class UserRepository {

    private static final RowMapper<UserRow> USER = (rs, n) -> new UserRow(uuid(rs, "id"), rs.getString("issuer"),
            rs.getString("subject"), rs.getString("email"), rs.getString("display_name"), rs.getString("status"),
            instant(rs, "created_at"), instant(rs, "updated_at"));

    private final JdbcClient jdbc;
    private final JsonColumns json;

    UserRepository(JdbcClient jdbc, JsonColumns json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Optional<UserRow> findByIdentity(String issuer, String subject) {
        return jdbc.sql("SELECT * FROM platform_user WHERE issuer = :issuer AND subject = :subject")
                .param("issuer", issuer)
                .param("subject", subject)
                .query(USER)
                .optional();
    }

    Optional<UserRow> findById(UUID id) {
        return jdbc.sql("SELECT * FROM platform_user WHERE id = :id").param("id", id).query(USER).optional();
    }

    List<UserRow> findByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT * FROM platform_user WHERE id IN (:ids)").param("ids", ids).query(USER).list();
    }

    /**
     * Inserts the user unless a concurrent request already did; the unique (issuer, subject) constraint makes
     * just-in-time provisioning race-free.
     */
    void insertIfAbsent(UUID id, String issuer, String subject, String email, String displayName, Instant now) {
        jdbc.sql("""
                INSERT INTO platform_user (id, issuer, subject, email, display_name, status, created_at, updated_at)
                VALUES (:id, :issuer, :subject, :email, :displayName, 'ACTIVE', :now, :now)
                ON CONFLICT (issuer, subject) DO NOTHING
                """)
                .param("id", id)
                .param("issuer", issuer)
                .param("subject", subject)
                .param("email", email)
                .param("displayName", displayName)
                .param("now", ts(now))
                .update();
        jdbc.sql("""
                INSERT INTO user_profile (user_id, preferences, version, updated_at)
                SELECT u.id, '{}'::jsonb, 0, :now FROM platform_user u
                WHERE u.issuer = :issuer AND u.subject = :subject
                ON CONFLICT (user_id) DO NOTHING
                """)
                .param("issuer", issuer)
                .param("subject", subject)
                .param("now", ts(now))
                .update();
    }

    void updateClaims(UUID id, String email, String displayName, Instant now) {
        jdbc.sql("UPDATE platform_user SET email = :email, display_name = :displayName, updated_at = :now WHERE id = :id")
                .param("id", id)
                .param("email", email)
                .param("displayName", displayName)
                .param("now", ts(now))
                .update();
    }

    Optional<ProfileRow> findProfile(UUID userId) {
        return jdbc.sql("SELECT * FROM user_profile WHERE user_id = :id")
                .param("id", userId)
                .query((rs, n) -> new ProfileRow(uuid(rs, "user_id"), rs.getString("locale"),
                        rs.getString("time_zone"), json.readObject(rs.getString("preferences")), rs.getLong("version"),
                        instant(rs, "updated_at")))
                .optional();
    }

    void updateProfile(ProfileRow profile) {
        jdbc.sql("""
                UPDATE user_profile
                   SET locale = :locale, time_zone = :timeZone, preferences = CAST(:preferences AS jsonb),
                       version = version + 1, updated_at = :updatedAt
                 WHERE user_id = :userId
                """)
                .param("userId", profile.userId())
                .param("locale", profile.locale())
                .param("timeZone", profile.timeZone())
                .param("preferences", json.write(profile.preferences()))
                .param("updatedAt", ts(profile.updatedAt()))
                .update();
    }
}
