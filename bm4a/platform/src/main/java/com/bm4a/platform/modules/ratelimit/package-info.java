/**
 * Redis-backed fixed-window rate limiting of mutating API calls ({@code POST}, {@code PUT}, {@code PATCH},
 * {@code DELETE} under {@code /api/}) per authenticated identity. Redis is not a source of truth: when it is
 * unavailable, requests are let through (fail open).
 */
@ApplicationModule(id = "ratelimit", displayName = "Rate limiting", allowedDependencies = "shared")
package com.bm4a.platform.modules.ratelimit;

import org.springframework.modulith.ApplicationModule;
