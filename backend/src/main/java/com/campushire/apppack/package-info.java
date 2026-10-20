/**
 * Installs the CampusHire app pack ({@code campushire/campushire-pack.json}) into an organization through the
 * platform's pack installer, stores the institute's allowed student e-mail domains and makes the installing admin the
 * placement officer. Other modules ask {@link CampusHirePack} whether an organization is set up.
 */
@ApplicationModule(id = "apppack", displayName = "CampusHire app pack",
        allowedDependencies = { "shared", "users", "authorization", "tenancy", "packs" })
package com.campushire.apppack;

import org.springframework.modulith.ApplicationModule;
