package com.bm4a.platform.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Like {@link IntegrationTest}, plus Redis (notification fan-out) and a Mailpit SMTP server, on a real HTTP port with
 * virtual threads. All tests using this annotation share one context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "spring.threads.virtual.enabled=true",
        "bm4a.app.name=CampusHire",
        "bm4a.notifications.stream.redis-enabled=true",
        "bm4a.notifications.email.poll-interval=200ms",
        "bm4a.notifications.email.from=Platform Tests <noreply@bm4a.test>",
        "bm4a.notifications.email.smtp.starttls=false",
        "bm4a.notifications.email.smtp.timeout=2s" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({ TestcontainersConfiguration.class, TestSecurityConfiguration.class, TestRecordWriteGuard.class,
        RedisMailContainers.class })
public @interface RedisMailIntegrationTest {
}
