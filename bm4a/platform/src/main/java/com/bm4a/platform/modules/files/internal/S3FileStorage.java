package com.bm4a.platform.modules.files.internal;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bm4a.platform.modules.shared.error.ApiException;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3-compatible object store (MinIO on-prem and locally, or AWS S3). Checksums are only sent when an operation
 * requires them, which keeps the adapter compatible with stores that do not implement the newer S3 integrity
 * headers.
 */
class S3FileStorage implements FileStorage, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(S3FileStorage.class);

    private final S3Client client;
    private final String bucket;

    S3FileStorage(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    static S3FileStorage create(FilesProperties.S3 settings) {
        AwsCredentialsProvider credentials = settings.accessKey() == null || settings.accessKey().isBlank()
                ? DefaultCredentialsProvider.builder().build()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(settings.accessKey(),
                        settings.secretKey()));
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(settings.region()))
                .credentialsProvider(credentials)
                .forcePathStyle(settings.pathStyle())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(60)));
        if (settings.endpoint() != null && !settings.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(settings.endpoint()));
        }
        S3FileStorage storage = new S3FileStorage(builder.build(), settings.bucket());
        storage.ensureBucket();
        return storage;
    }

    /** Creates the bucket when missing; an unreachable store is logged, not fatal (operations then fail with 503). */
    void ensureBucket() {
        try {
            client.headBucket(request -> request.bucket(bucket));
        } catch (NoSuchBucketException ex) {
            try {
                client.createBucket(request -> request.bucket(bucket));
                log.info("Created file storage bucket {}", bucket);
            } catch (SdkException createFailure) {
                log.warn("Cannot create file storage bucket {}: {}", bucket, createFailure.getMessage());
            }
        } catch (SdkException ex) {
            log.warn("File storage bucket {} is not reachable: {}", bucket, ex.getMessage());
        }
    }

    @Override
    public void put(String key, Path source, long sizeBytes, String contentType) {
        try {
            client.putObject(request -> request.bucket(bucket).key(key).contentType(contentType)
                    .contentLength(sizeBytes), RequestBody.fromFile(source));
        } catch (SdkException ex) {
            throw unavailable(ex);
        }
    }

    @Override
    public InputStream open(String key) {
        try {
            return client.getObject(request -> request.bucket(bucket).key(key));
        } catch (NoSuchKeyException ex) {
            throw ApiException.notFound("File");
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                throw ApiException.notFound("File");
            }
            throw unavailable(ex);
        } catch (SdkException ex) {
            throw unavailable(ex);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(request -> request.bucket(bucket).key(key));
        } catch (SdkException ex) {
            throw unavailable(ex);
        }
    }

    @Override
    public void close() {
        client.close();
    }

    private static ApiException unavailable(SdkException ex) {
        log.warn("File storage operation failed: {}", ex.getMessage());
        ApiException failure = ApiException.unavailable("file_storage_unavailable", "File storage is unavailable");
        failure.initCause(ex);
        return failure;
    }
}
