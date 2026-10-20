package com.bm4a.platform.modules.shared.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * JDBC conversions used by the {@code JdbcClient}-based repositories. All timestamps are persisted as UTC
 * {@code timestamptz} with microsecond precision (PostgreSQL's resolution).
 */
public final class SqlValues {

    private SqlValues() {
    }

    public static Instant now(java.time.Clock clock) {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    public static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }
}
