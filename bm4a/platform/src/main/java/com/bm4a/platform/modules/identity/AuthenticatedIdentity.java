package com.bm4a.platform.modules.identity;

import java.util.Optional;

/**
 * Access to the claims of the currently authenticated caller (validated JWT).
 */
public interface AuthenticatedIdentity {

    Optional<IdentityClaims> current();
}
