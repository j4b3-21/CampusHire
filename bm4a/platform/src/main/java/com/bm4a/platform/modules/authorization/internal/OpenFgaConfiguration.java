package com.bm4a.platform.modules.authorization.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ApiToken;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import dev.openfga.sdk.api.configuration.Credentials;
import dev.openfga.sdk.api.model.CreateStoreRequest;
import dev.openfga.sdk.api.model.Store;
import dev.openfga.sdk.api.model.WriteAuthorizationModelRequest;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenFgaProperties.class)
class OpenFgaConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OpenFgaConfiguration.class);
    static final String MODEL_RESOURCE = "openfga/model.json";

    @Bean
    OpenFgaClient openFgaClient(OpenFgaProperties properties) throws Exception {
        ClientConfiguration configuration = new ClientConfiguration()
                .apiUrl(properties.apiUrl())
                .connectTimeout(properties.requestTimeout())
                .readTimeout(properties.requestTimeout())
                .maxRetries(2);
        if (properties.apiToken() != null && !properties.apiToken().isBlank()) {
            configuration.credentials(new Credentials(new ApiToken(properties.apiToken())));
        }
        OpenFgaClient client = new OpenFgaClient(configuration);
        if (properties.bootstrap()) {
            bootstrap(client, properties);
        } else {
            if (isBlank(properties.storeId()) || isBlank(properties.modelId())) {
                throw new IllegalStateException(
                        "bm4a.authz.openfga.store-id and model-id are required when bootstrap is disabled");
            }
            client.setStoreId(properties.storeId());
            client.setAuthorizationModelId(properties.modelId());
        }
        return client;
    }

    /**
     * Development/test convenience: find or create the store by name and write the bundled model. Production
     * deployments provision the store and model with deployment tooling and configure their IDs explicitly.
     */
    private static void bootstrap(OpenFgaClient client, OpenFgaProperties properties) throws Exception {
        String storeId = client.listStores().get(30, TimeUnit.SECONDS).getStores().stream()
                .filter(store -> properties.storeName().equals(store.getName()))
                .map(Store::getId)
                .findFirst()
                .orElse(null);
        if (storeId == null) {
            storeId = client.createStore(new CreateStoreRequest().name(properties.storeName()))
                    .get(30, TimeUnit.SECONDS).getId();
            log.info("Created OpenFGA store '{}' ({})", properties.storeName(), storeId);
        }
        client.setStoreId(storeId);
        String modelId = client.writeAuthorizationModel(readModel()).get(30, TimeUnit.SECONDS)
                .getAuthorizationModelId();
        client.setAuthorizationModelId(modelId);
        log.info("OpenFGA bootstrap complete: store {} model {}", storeId, modelId);
    }

    static WriteAuthorizationModelRequest readModel() throws IOException {
        JsonMapper mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        try (InputStream in = new ClassPathResource(MODEL_RESOURCE).getInputStream()) {
            return mapper.readValue(in, WriteAuthorizationModelRequest.class);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static Throwable unwrap(Throwable ex) {
        return ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
    }
}
