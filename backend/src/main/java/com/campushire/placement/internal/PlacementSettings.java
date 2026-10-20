package com.campushire.placement.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.bm4a.platform.modules.tenancy.OrganizationSettings;

/**
 * The institute's placement policy and the clock. Registration windows are instants compared in UTC; calendar dates
 * (offer deadlines, follow-ups) are compared in the institute's time zone ({@code campushire.time-zone}).
 */
@Component
class PlacementSettings {

    private final OrganizationSettings settings;
    private final Clock clock;
    private final ZoneId zone;

    PlacementSettings(OrganizationSettings settings, Clock clock,
            @Value("${campushire.time-zone:Asia/Kolkata}") ZoneId zone) {
        this.settings = settings;
        this.clock = clock;
        this.zone = zone;
    }

    PlacementPolicy policy(UUID organizationId) {
        return PlacementPolicy.from(settings.get(organizationId));
    }

    Instant now() {
        return SqlValues.now(clock);
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }
}
