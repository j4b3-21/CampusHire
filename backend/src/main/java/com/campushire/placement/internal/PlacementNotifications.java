package com.campushire.placement.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/**
 * Tells students about placement events (in-app and e-mail). Implementations write in the caller's transaction where
 * they persist anything, so nothing is sent for a rolled-back change; recipients are user IDs of linked student
 * accounts and may be thousands.
 */
interface PlacementNotifications {

    void drivePublished(UUID organizationId, UUID driveId, String driveTitle, Collection<UUID> studentUserIds);

    void driveCancelled(UUID organizationId, UUID driveId, String driveTitle, Collection<UUID> studentUserIds);

    void stageChanged(UUID organizationId, UUID applicationId, String driveTitle, String fromStage, String toStage,
            UUID studentUserId);

    void offerExtended(UUID organizationId, UUID offerId, String companyName, UUID studentUserId);

    void interviewScheduled(UUID organizationId, UUID interviewId, String driveTitle, String roundName,
            Instant scheduledAt, UUID studentUserId);
}
