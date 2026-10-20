package com.bm4a.platform.modules.authorization;

import org.springframework.http.HttpStatus;

import com.bm4a.platform.modules.shared.error.ApiException;

/**
 * The authorization backend could not produce a decision. Mapped to 503; the protected operation is not executed.
 */
public class AuthorizationUnavailableException extends ApiException {

    public AuthorizationUnavailableException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "authorization_unavailable",
                "Authorization service is unavailable; the request was not executed");
    }
}
