package com.bm4a.platform.modules.identity.internal;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.bm4a.platform.modules.shared.error.ProblemResponses;
import com.bm4a.platform.modules.shared.error.Problems;

import jakarta.servlet.DispatcherType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stateless resource-server security. Everything under {@code /api/**} requires a valid bearer token; only health
 * probes, info and API documentation are public. Authorization decisions beyond "authenticated" are made by the
 * tenancy/authorization modules, never by this filter chain.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
class SecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, AuthenticationEntryPoint entryPoint,
            AccessDeniedHandler accessDeniedHandler, JwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Completing an async request (Server-Sent Events) re-dispatches a request that was already
                        // authorized; the bearer token filter does not run again for it.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(JwtValidation.validator(properties));
        return decoder;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName(JwtClaimNames.SUB);
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfiguration::realmRoles);
        return converter;
    }

    @Bean
    AuthenticationEntryPoint problemAuthenticationEntryPoint(JsonMapper jsonMapper) {
        BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
        return (request, response, authException) -> {
            log.info("Authentication failed for {} {}: {}", request.getMethod(), request.getRequestURI(),
                    authException.getClass().getSimpleName());
            bearer.commence(request, response, authException);
            ProblemResponses.write(response,
                    Problems.of(HttpStatus.UNAUTHORIZED, "unauthenticated", "A valid bearer token is required"),
                    jsonMapper);
        };
    }

    @Bean
    AccessDeniedHandler problemAccessDeniedHandler(JsonMapper jsonMapper) {
        BearerTokenAccessDeniedHandler bearer = new BearerTokenAccessDeniedHandler();
        return (request, response, deniedException) -> {
            bearer.handle(request, response, deniedException);
            ProblemResponses.write(response, Problems.of(HttpStatus.FORBIDDEN, "access_denied", "Access denied"),
                    jsonMapper);
        };
    }

    /**
     * Keycloak realm roles ({@code realm_access.roles}) become {@code ROLE_<name>} authorities. They are only used
     * for platform-level administration; tenant permissions come from OpenFGA.
     */
    static Collection<GrantedAuthority> realmRoles(Jwt jwt) {
        Object realmAccess = jwt.getClaims().get("realm_access");
        if (!(realmAccess instanceof Map<?, ?> access) || !(access.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}
