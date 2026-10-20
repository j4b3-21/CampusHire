package com.campushire.campushire.modules.auth.mapper;

import com.campushire.campushire.modules.auth.dto.AuthUserResponse;
import com.campushire.campushire.modules.users.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AuthMapper {

    public AuthUserResponse toResponse(
            User user,
            List<String> roles
    ) {
        return AuthUserResponse.builder()
                .id(user.getId())
                .keycloakId(user.getKeycloakId())
                .email(user.getEmail())
                .username(user.getUsername())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .profilePicture(user.getProfilePicture())
                .instituteId(user.getInstituteId())
                .active(user.isActive())
                .roles(roles)
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }

    public List<String> extractRoles(List<? extends GrantedAuthority> authorities) {
        return authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }
}
