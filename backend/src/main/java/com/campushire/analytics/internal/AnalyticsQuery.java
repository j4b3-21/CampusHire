package com.campushire.analytics.internal;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;

import com.bm4a.platform.modules.shared.error.ApiException;

/**
 * Optional filters shared by every analytics endpoint and the report. {@code from} and {@code to} are inclusive
 * calendar dates in the institute's time zone and apply to the creation time of offers and applications.
 */
record AnalyticsQuery(UUID batchId, UUID departmentId, @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

    /** Checked here rather than in the constructor, which request binding would turn into a server error. */
    AnalyticsQuery validated() {
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.validation("from", "invalid_range", "from must not be after to");
        }
        return this;
    }

    String key() {
        return "batch=" + value(batchId) + "&department=" + value(departmentId) + "&from=" + value(from) + "&to="
                + value(to);
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }
}
