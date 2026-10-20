package com.campushire.placement;

import java.util.UUID;

/** Placement facts for other CampusHire modules (dashboards). */
public interface PlacementInsights {

    /** Drives whose registration is open right now and for which the student is eligible. */
    long eligibleOpenDrives(UUID organizationId, UUID studentId);
}
