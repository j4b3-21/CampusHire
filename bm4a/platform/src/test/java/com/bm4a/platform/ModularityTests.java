package com.bm4a.platform;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Verifies module boundaries: no cycles, and modules only use each other's published API (root package or
 * named interfaces), never {@code internal} or {@code web} sub-packages.
 */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(PlatformApplication.class);

    @Test
    void verifiesModuleStructure() {
        modules.verify();
    }

    @Test
    void writesModuleDocumentation() {
        new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
