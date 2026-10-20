package com.bm4a.platform.modules.shared.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * OpenAPI description served at {@code /v3/api-docs} and rendered at {@code /swagger-ui.html}. Disable both in
 * production with {@code springdoc.api-docs.enabled=false} if the API surface should not be published.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    @Bean
    OpenAPI bm4aOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("BM4A Platform API")
                        .version("v1")
                        .description("""
                                Metadata-driven multi-tenant platform API. All /api/v1 endpoints require a Keycloak \
                                access token (audience bm4a-api). Tenant-scoped endpoints live under \
                                /api/v1/orgs/{orgId}; the organization is verified against the caller's membership. \
                                Errors are RFC 9457 problem details with a stable `code`, optional field `errors` and \
                                a `correlationId`. Modifying requests on versioned resources require If-Match."""))
                .components(new Components().addSecuritySchemes("bearer", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
