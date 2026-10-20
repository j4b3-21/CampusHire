package com.bm4a.platform.modules.users.web;

import java.time.Instant;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bm4a.platform.modules.users.CurrentUser;
import com.bm4a.platform.modules.users.internal.ProfileRow;
import com.bm4a.platform.modules.users.internal.UserService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/me")
class MeController {

    private final UserService users;

    MeController(UserService users) {
        this.users = users;
    }

    @GetMapping
    MeResponse me(CurrentUser user) {
        return MeResponse.of(user, users.profile(user.id()));
    }

    /**
     * Partial update: omitted or {@code null} properties are left unchanged; an empty string clears locale/timeZone.
     */
    @PatchMapping("/profile")
    MeResponse updateProfile(CurrentUser user, @Valid @RequestBody ProfileUpdateRequest request) {
        ProfileRow profile = users.updateProfile(user.id(), request.locale(), request.timeZone(), request.preferences());
        return MeResponse.of(user, profile);
    }

    record ProfileUpdateRequest(@Size(max = 35) String locale, @Size(max = 64) String timeZone, JsonNode preferences) {
    }

    record ProfileResponse(String locale, String timeZone, JsonNode preferences, Instant updatedAt) {
    }

    record MeResponse(UUID id, String email, String displayName, boolean platformAdmin, boolean emailVerified,
            ProfileResponse profile) {

        static MeResponse of(CurrentUser user, ProfileRow profile) {
            return new MeResponse(user.id(), user.email(), user.displayName(), user.platformAdmin(),
                    user.emailVerified(),
                    new ProfileResponse(profile.locale(), profile.timeZone(), profile.preferences(),
                            profile.updatedAt()));
        }
    }
}
