package com.bm4a.platform.modules.authorization.internal;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.bm4a.platform.modules.authorization.AuthorizationService;
import com.bm4a.platform.modules.authorization.AuthorizationUnavailableException;
import com.bm4a.platform.modules.authorization.Permission;
import com.bm4a.platform.modules.authorization.RelationshipTuple;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.openfga.sdk.api.client.model.ClientCheckResponse;
import dev.openfga.sdk.errors.FgaInvalidParameterException;

@Service
class OpenFgaAuthorizationService implements AuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(OpenFgaAuthorizationService.class);

    private final OpenFgaClient client;
    private final OpenFgaProperties properties;

    OpenFgaAuthorizationService(OpenFgaClient client, OpenFgaProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public boolean isAllowed(UUID userId, Permission permission, UUID organizationId) {
        ClientCheckRequest request = new ClientCheckRequest()
                .user(RelationshipTuple.userRef(userId))
                .relation(permission.relation())
                ._object(RelationshipTuple.organizationRef(organizationId));
        try {
            ClientCheckResponse response = client.check(request)
                    .get(properties.requestTimeout().toMillis() * 3, TimeUnit.MILLISECONDS);
            return Boolean.TRUE.equals(response.getAllowed());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AuthorizationUnavailableException();
        } catch (ExecutionException | TimeoutException | FgaInvalidParameterException | RuntimeException ex) {
            log.error("OpenFGA check failed (failing closed): {}", OpenFgaConfiguration.unwrap(ex).toString());
            throw new AuthorizationUnavailableException();
        }
    }
}
