package com.campushire.campushire.modules.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakAuthoritiesConverterTests {

	private final KeycloakAuthoritiesConverter converter = new KeycloakAuthoritiesConverter();

	@Test
	void convertsScopesRealmRolesAndClientRoles() {
		Jwt jwt = Jwt.withTokenValue("token")
				.header("alg", "none")
				.claim("scope", "openid profile email")
				.claim("realm_access", Map.of("roles", List.of("recruiter", "student")))
				.claim("resource_access", Map.of("crm", Map.of("roles", List.of("admin"))))
				.build();

		assertThat(converter.convert(jwt))
				.extracting(GrantedAuthority::getAuthority)
				.contains("SCOPE_openid", "SCOPE_profile", "SCOPE_email", "ROLE_recruiter", "ROLE_student", "ROLE_crm_admin");
	}
}
