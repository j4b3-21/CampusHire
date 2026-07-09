package com.campushire.campushire.modules.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:campushire;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.jpa.hibernate.ddl-auto=none"
})
class AuthControllerTests {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private AuthUserProfileRepository repository;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
	}

	@Test
	void loginRedirectsToKeycloakAuthorizationEndpoint() throws Exception {
		mockMvc.perform(get("/auth/login"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/oauth2/authorization/keycloak"));
	}

	@Test
	void meReturnsCurrentUserAndSyncsProfile() throws Exception {
		mockMvc.perform(get("/auth/me").with(jwt()
				.authorities(
						List.of(
								new SimpleGrantedAuthority("SCOPE_openid"),
								new SimpleGrantedAuthority("SCOPE_profile"),
								new SimpleGrantedAuthority("SCOPE_email"),
								new SimpleGrantedAuthority("ROLE_recruiter")))
				.jwt(token -> token
						.subject("user-123")
						.claim("preferred_username", "maya")
						.claim("email", "maya@example.com")
						.claim("name", "Maya Singh")
						.claim("scope", "openid profile email")
						.claim("realm_access", Map.of("roles", List.of("recruiter"))))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.keycloakSubject").value("user-123"))
				.andExpect(jsonPath("$.username").value("maya"))
				.andExpect(jsonPath("$.email").value("maya@example.com"))
				.andExpect(jsonPath("$.displayName").value("Maya Singh"))
				.andExpect(jsonPath("$.authorities").isArray())
				.andExpect(jsonPath("$.authorities[?(@ == 'ROLE_recruiter')]").exists())
				.andExpect(jsonPath("$.authorities").isArray());

		assertThat(repository.findByKeycloakSubject("user-123")).isPresent();
	}
}
