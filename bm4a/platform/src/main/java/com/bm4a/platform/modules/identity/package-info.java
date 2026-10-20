/**
 * Authentication: OAuth2 resource server configuration, JWT validation and claim extraction. Keycloak is the
 * identity provider; this module never stores credentials. Exposes {@link IdentityClaims} and
 * {@link AuthenticatedIdentity}.
 */
@ApplicationModule(id = "identity", displayName = "Identity", allowedDependencies = "shared")
package com.bm4a.platform.modules.identity;

import org.springframework.modulith.ApplicationModule;
