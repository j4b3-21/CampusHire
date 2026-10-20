package com.bm4a.platform.modules.files.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FilesProperties.class)
class FilesConfiguration {

    @Bean
    @ConditionalOnProperty(name = "bm4a.files.storage", havingValue = "s3")
    S3FileStorage s3FileStorage(FilesProperties properties) {
        return S3FileStorage.create(properties.s3());
    }

    @Bean
    @ConditionalOnProperty(name = "bm4a.files.storage", havingValue = "filesystem", matchIfMissing = true)
    FilesystemFileStorage filesystemFileStorage(FilesProperties properties) {
        return new FilesystemFileStorage(properties.localDir());
    }
}
