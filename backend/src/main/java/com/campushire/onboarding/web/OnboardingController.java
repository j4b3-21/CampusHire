package com.campushire.onboarding.web;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.bm4a.platform.modules.users.CurrentUser;
import com.campushire.onboarding.internal.MeService;
import com.campushire.onboarding.internal.MeService.Me;
import com.campushire.onboarding.internal.OnboardingService;
import com.campushire.onboarding.internal.OnboardingService.ClaimResult;
import com.campushire.onboarding.internal.OnboardingService.Discovery;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
class OnboardingController {

    private final OnboardingService onboarding;
    private final MeService me;

    OnboardingController(OnboardingService onboarding, MeService me) {
        this.onboarding = onboarding;
        this.me = me;
    }

    /** Authenticated users, no membership needed. */
    @GetMapping("/api/v1/campushire/onboarding")
    Discovery discover(CurrentUser user) {
        return onboarding.discover(user);
    }

    @PostMapping("/api/v1/campushire/onboarding/claim")
    ClaimResult claim(CurrentUser user, @Valid @RequestBody ClaimRequest request) {
        return onboarding.claim(user, request.organizationId());
    }

    @GetMapping("/api/v1/orgs/{orgId}/campushire/me")
    Me me(CurrentUser user, @PathVariable UUID orgId) {
        return me.me(user, orgId);
    }

    record ClaimRequest(@NotNull UUID organizationId) {
    }
}
