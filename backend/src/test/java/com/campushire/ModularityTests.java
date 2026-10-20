package com.campushire;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;

/**
 * Verifies the boundaries of BM4A platform modules and CampusHire modules together: no cycles, and modules only use
 * each other's published API.
 */
class ModularityTests {

    /** The platform test-jar is on the test classpath; its classes are verified by the platform's own build. */
    private static final DescribedPredicate<JavaClass> PLATFORM_TEST_CLASSES = resideInAPackage("com.bm4a.platform..")
            .and(simpleNameEndingWith("Test").or(simpleNameEndingWith("Tests"))
                    .or(resideInAPackage("com.bm4a.platform.support..")));

    private final ApplicationModules modules = ApplicationModules.of(CampusHireApplication.class,
            PLATFORM_TEST_CLASSES);

    @Test
    void verifiesModuleStructure() {
        modules.verify();
    }

    @Test
    void writesModuleDocumentation() {
        new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
