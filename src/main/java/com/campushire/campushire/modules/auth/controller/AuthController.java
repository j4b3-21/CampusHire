package com.campushire.campushire.modules.auth.controller;

import com.campushire.campushire.modules.auth.dto.ApiResponse;
import com.campushire.campushire.modules.auth.dto.AuthUserResponse;
import com.campushire.campushire.modules.auth.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;

	@GetMapping("/me")
	public ResponseEntity<ApiResponse<AuthUserResponse>> me(
			@AuthenticationPrincipal Jwt jwt,
			Authentication authentication
	) {

		return ResponseEntity.ok(
				ApiResponse.success(
						"User fetched successfully",
						authService.getCurrentUser(jwt, authentication)
				)
		);
	}
}