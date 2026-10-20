package com.bm4a.platform.modules.authorization.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * @param apiUrl OpenFGA HTTP API base URL
 * @param apiToken pre-shared key sent as bearer token (empty = no authentication, development only)
 * @param storeName store looked up or created when {@code bootstrap} is enabled
 * @param storeId explicit store ID (required when {@code bootstrap} is disabled)
 * @param modelId explicit authorization model ID (required when {@code bootstrap} is disabled)
 * @param bootstrap find-or-create the store and write {@code openfga/model.json} at startup (local/test only)
 * @param requestTimeout upper bound for a single check or write
 * @param resubmitInterval how often failed tuple synchronizations are retried
 */
@Validated
@ConfigurationProperties("bm4a.authz.openfga")
public record OpenFgaProperties(
        @NotBlank String apiUrl,
        String apiToken,
        @DefaultValue("bm4a") String storeName,
        String storeId,
        String modelId,
        @DefaultValue("false") boolean bootstrap,
        @DefaultValue("3s") @NotNull Duration requestTimeout,
        @DefaultValue("30s") @NotNull Duration resubmitInterval) {
}
