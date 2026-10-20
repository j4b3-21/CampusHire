package com.bm4a.platform.modules.users;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read access to internal users for other modules.
 */
public interface UserDirectory {

    Optional<UserSummary> findActive(UUID userId);

    Map<UUID, UserSummary> findAll(Collection<UUID> userIds);

    record UserSummary(UUID id, String email, String displayName) {
    }
}
