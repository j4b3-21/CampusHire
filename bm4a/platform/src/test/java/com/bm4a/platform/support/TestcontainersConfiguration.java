package com.bm4a.platform.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.openfga.OpenFGAContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL and OpenFGA (same versions as infrastructure/), shared by every integration test context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String OPENFGA_IMAGE = "openfga/openfga:v1.22.0";
    public static final String OPENFGA_PRESHARED_KEY = "test-preshared-key";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
    }

    @Bean
    OpenFGAContainer openfga() {
        return new OpenFGAContainer(DockerImageName.parse(OPENFGA_IMAGE))
                .withEnv("OPENFGA_AUTHN_METHOD", "preshared")
                .withEnv("OPENFGA_AUTHN_PRESHARED_KEYS", OPENFGA_PRESHARED_KEY)
                .withEnv("OPENFGA_PLAYGROUND_ENABLED", "false");
    }

    @Bean
    DynamicPropertyRegistrar openfgaProperties(OpenFGAContainer openfga) {
        return registry -> {
            registry.add("bm4a.authz.openfga.api-url", openfga::getHttpEndpoint);
            registry.add("bm4a.authz.openfga.api-token", () -> OPENFGA_PRESHARED_KEY);
            registry.add("bm4a.authz.openfga.bootstrap", () -> "true");
        };
    }
}
