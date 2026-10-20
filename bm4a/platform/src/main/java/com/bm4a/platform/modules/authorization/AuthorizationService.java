package com.bm4a.platform.modules.authorization;

import java.util.UUID;

/**
 * Authorization decisions. Implementations must fail closed: when a decision cannot be obtained they throw
 * {@link AuthorizationUnavailableException} instead of returning {@code true}.
 */
public interface AuthorizationService {

    boolean isAllowed(UUID userId, Permission permission, UUID organizationId);
}
