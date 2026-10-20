package com.bm4a.platform.support;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Redis and Mailpit (SMTP on 1025, HTTP API on 8025) for {@link RedisMailIntegrationTest}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisMailContainers {

    public static final int MAILPIT_SMTP = 1025;
    public static final int MAILPIT_API = 8025;

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:8.2")).withExposedPorts(6379);
    }

    @Bean
    GenericContainer<?> mailpitContainer() {
        return new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.27"))
                .withExposedPorts(MAILPIT_SMTP, MAILPIT_API);
    }

    @Bean
    DynamicPropertyRegistrar mailpitProperties(@Qualifier("mailpitContainer") GenericContainer<?> mailpitContainer) {
        return registry -> {
            registry.add("bm4a.notifications.email.smtp.host", mailpitContainer::getHost);
            registry.add("bm4a.notifications.email.smtp.port", () -> mailpitContainer.getMappedPort(MAILPIT_SMTP));
        };
    }

    /** Base URL of Mailpit's HTTP API, e.g. {@code <base>/api/v1/messages}. */
    public static String mailpitApi(GenericContainer<?> mailpit) {
        return "http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(MAILPIT_API);
    }

    /** Freezes the container's processes: connections hang until {@link #unpause}, like an unresponsive server. */
    public static void pause(GenericContainer<?> container) {
        container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
    }

    public static void unpause(GenericContainer<?> container) {
        container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
    }
}
