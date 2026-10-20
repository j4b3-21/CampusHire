package com.bm4a.platform.modules.files.internal;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * @param storage object store adapter ({@code FILES_STORAGE}: {@code filesystem} or {@code s3})
 * @param localDir root directory of the filesystem adapter ({@code FILES_LOCAL_DIR})
 * @param maxSize largest accepted file
 */
@Validated
@ConfigurationProperties("bm4a.files")
public record FilesProperties(@DefaultValue("filesystem") @NotNull StorageType storage, Path localDir,
        @DefaultValue("25MB") @NotNull DataSize maxSize, @DefaultValue @Valid S3 s3) {

    public enum StorageType {
        FILESYSTEM, S3
    }

    public FilesProperties {
        if (localDir == null) {
            localDir = Path.of(System.getProperty("java.io.tmpdir"), "bm4a-files");
        }
    }

    /**
     * @param endpoint e.g. {@code http://minio:9000}; empty for AWS S3
     * @param accessKey static credentials; when empty the default AWS credential chain applies
     * @param pathStyle path-style bucket addressing (MinIO)
     */
    public record S3(String endpoint, @DefaultValue("us-east-1") @NotBlank String region,
            @DefaultValue("bm4a-files") @NotBlank String bucket, String accessKey, String secretKey,
            @DefaultValue("true") boolean pathStyle) {
    }
}
