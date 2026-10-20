/**
 * Authorization port and its OpenFGA adapter. Other modules depend only on {@link AuthorizationService},
 * {@link Permission}, {@link OrganizationRelation} and the {@link RelationshipTuplesChanged} event; the OpenFGA SDK is
 * confined to the {@code internal} package. Decisions fail closed.
 */
@ApplicationModule(id = "authorization", displayName = "Authorization", allowedDependencies = "shared")
package com.bm4a.platform.modules.authorization;

import org.springframework.modulith.ApplicationModule;
