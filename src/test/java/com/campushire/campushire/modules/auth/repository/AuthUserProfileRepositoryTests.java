package com.campushire.campushire.modules.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import com.campushire.campushire.modules.auth.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:campushire_repo;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.jpa.hibernate.ddl-auto=none"
})
class AuthUserProfileRepositoryTests {

	@Autowired
	private AuthUserProfileRepository repository;

	@Test
	void savesAndFindsByKeycloakSubject() {
		User profile = new User();
		profile.setId(UUID.randomUUID());
		profile.setKeycloakSubject("subject-1");
		profile.setEmail("test@example.com");
		profile.setUsername("test");
		profile.setDisplayName("Test User");
		profile.setAuthorities(List.of("ROLE_recruiter"));
		profile.setEnabled(true);

		repository.save(profile);

		assertThat(repository.findByKeycloakSubject("subject-1")).isPresent();
	}
}
