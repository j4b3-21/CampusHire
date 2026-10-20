package com.campushire.apppack;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Installation state of the CampusHire app pack. */
public interface CampusHirePack {

    String KEY = "campushire";

    /** The installed pack, empty when the organization has not been set up. */
    Optional<Installation> installation(UUID organizationId);

    /** Version of the pack shipped with this build. */
    int availableVersion();

    record Installation(int version, Instant installedAt, UUID installedBy) {
    }
}
