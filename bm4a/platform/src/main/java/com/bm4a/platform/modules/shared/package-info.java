/**
 * Genuinely cross-cutting building blocks: error model, paging, correlation IDs, HTTP precondition helpers.
 * Nothing domain-specific belongs here.
 */
@ApplicationModule(id = "shared", displayName = "Shared", type = ApplicationModule.Type.OPEN)
package com.bm4a.platform.modules.shared;

import org.springframework.modulith.ApplicationModule;
