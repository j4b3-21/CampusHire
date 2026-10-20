package com.campushire;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Verifies the boundaries of BM4A platform modules and CampusHire modules together: no cycles, and modules only use
 * each other's published API.
 */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(CampusHireApplication.class);

    @Test
    void verifiesModuleStructure() {
        modules.verify();
    }

    @Test
    void writesModuleDocumentation() {
        new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
