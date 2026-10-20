/**
 * Application users: maps verified identities to internal user IDs (just-in-time provisioning), and owns user
 * profiles and preferences. Exposes {@link CurrentUser} (injectable into controller methods) and
 * {@link UserDirectory}.
 */
@ApplicationModule(id = "users", displayName = "Users", allowedDependencies = { "shared", "identity" })
package com.bm4a.platform.modules.users;

import org.springframework.modulith.ApplicationModule;
