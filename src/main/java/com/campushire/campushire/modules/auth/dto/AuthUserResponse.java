package com.campushire.campushire.modules.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthUserResponse {

    private UUID id;

    private String keycloakId;

    private String email;

    private String username;

    private String firstName;

    private String lastName;

    private String profilePicture;

    private UUID instituteId;

    private boolean active;

    private List<String> roles;

    private Instant lastLoginAt;

    private Instant createdAt;

    private Instant updatedAt;
}
