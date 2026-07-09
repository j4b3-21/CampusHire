package com.campushire.campushire.modules.auth.service;

import com.campushire.campushire.modules.auth.dto.AuthUserResponse;
import com.campushire.campushire.modules.auth.entity.User;
import com.campushire.campushire.modules.auth.mapper.AuthMapper;
import com.campushire.campushire.modules.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final AuthMapper authMapper;

    public AuthUserResponse getCurrentUser(
            Jwt jwt,
            Authentication authentication
    ) {
        User user = syncCurrentUser(jwt);

        List<String> roles = authentication.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        return authMapper.toResponse(user, roles);
    }

    public User syncCurrentUser(Jwt jwt) {

        String keycloakId = jwt.getSubject();

        User user = userRepository
                .findByKeycloakId(keycloakId)
                .orElseGet(() -> createUser(jwt));

        updateUserFromJwt(user, jwt);

        user.setLastLoginAt(Instant.now());

        return userRepository.save(user);
    }

    private User createUser(Jwt jwt) {

        User user = new User();

        user.setKeycloakId(jwt.getSubject());

        return user;
    }

    private void updateUserFromJwt(
            User user,
            Jwt jwt
    ) {

        user.setEmail(jwt.getClaimAsString("email"));

        user.setUsername(
                jwt.getClaimAsString("preferred_username")
        );

        user.setFirstName(
                jwt.getClaimAsString("given_name")
        );

        user.setLastName(
                jwt.getClaimAsString("family_name")
        );

        user.setProfilePicture(
                jwt.getClaimAsString("picture")
        );

        user.setActive(true);
    }
}