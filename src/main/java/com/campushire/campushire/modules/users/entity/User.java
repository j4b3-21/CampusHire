package com.campushire.campushire.modules.users.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "keycloak_id", nullable = false, unique = true, length = 100)
	private String keycloakId;

	@Column(nullable = false, unique = true, length = 255)
	private String email;

	@Column(name = "username", unique = true, length = 100)
	private String username;

	@Column(name = "first_name", length = 100)
	private String firstName;

	@Column(name = "last_name", length = 100)
	private String lastName;

	@Column(name = "profile_picture")
	private String profilePicture;

	@Column(name = "institute_id")
	private UUID instituteId;

	@Column(name = "last_login_at")
	private Instant lastLoginAt;

	@Column(nullable = false)
	@Builder.Default
	private boolean active = true;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at")
	private Instant updatedAt;
	@PrePersist
	void onCreate() {
	    if (lastLoginAt == null) {
	        lastLoginAt = Instant.now();
	    }
	}
}
