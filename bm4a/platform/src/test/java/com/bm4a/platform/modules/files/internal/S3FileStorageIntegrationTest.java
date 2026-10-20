package com.bm4a.platform.modules.files.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.bm4a.platform.modules.shared.error.ApiException;

/** The S3 adapter against a real MinIO server (path-style addressing, static credentials, bucket creation). */
class S3FileStorageIntegrationTest {

    static final String MINIO_IMAGE = "pgsty/minio:RELEASE.2026-08-04T00-00-00Z";
    static final String USER = "bm4a-test";
    static final String PASSWORD = "bm4a-test-secret";

    static GenericContainer<?> minio;
    static S3FileStorage storage;

    @TempDir
    Path dir;

    @BeforeAll
    static void startMinio() {
        minio = new GenericContainer<>(DockerImageName.parse(MINIO_IMAGE))
                .withCommand("server", "/data")
                .withEnv("MINIO_ROOT_USER", USER)
                .withEnv("MINIO_ROOT_PASSWORD", PASSWORD)
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        minio.start();
        storage = S3FileStorage.create(new FilesProperties.S3(
                "http://" + minio.getHost() + ":" + minio.getMappedPort(9000), "us-east-1", "bm4a-test-files", USER,
                PASSWORD, true));
    }

    @AfterAll
    static void stopMinio() {
        if (storage != null) {
            storage.close();
        }
        if (minio != null) {
            minio.stop();
        }
    }

    @Test
    void storesReadsReplacesAndDeletesObjects() throws Exception {
        String key = "org-1/2026/10/" + java.util.UUID.randomUUID();
        Path source = Files.writeString(dir.resolve("a.txt"), "first version", StandardCharsets.UTF_8);
        storage.put(key, source, Files.size(source), "text/plain");
        try (InputStream content = storage.open(key)) {
            assertThat(new String(content.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("first version");
        }

        Path replacement = Files.write(dir.resolve("b.bin"), new byte[3 * 1024 * 1024]);
        storage.put(key, replacement, Files.size(replacement), "application/octet-stream");
        try (InputStream content = storage.open(key)) {
            assertThat(content.readAllBytes()).hasSize(3 * 1024 * 1024);
        }

        storage.delete(key);
        assertThatThrownBy(() -> storage.open(key)).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.code()).isEqualTo("not_found"));
        storage.delete(key);
    }

    @Test
    void unreachableStoreIsUnavailable() throws Exception {
        try (S3FileStorage offline = S3FileStorage.create(new FilesProperties.S3("http://127.0.0.1:9", "us-east-1",
                "nowhere", USER, PASSWORD, true))) {
            Path source = Files.writeString(dir.resolve("c.txt"), "x", StandardCharsets.UTF_8);
            assertThatThrownBy(() -> offline.put("k", source, 1, "text/plain"))
                    .isInstanceOfSatisfying(ApiException.class,
                            ex -> assertThat(ex.code()).isEqualTo("file_storage_unavailable"));
        }
    }
}
