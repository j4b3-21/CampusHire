package com.bm4a.platform.modules.ratelimit.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import com.bm4a.platform.modules.shared.error.ProblemResponses;
import com.bm4a.platform.modules.shared.error.Problems;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fixed-window limit on mutating {@code /api/} calls per JWT identity ({@code iss} + {@code sub}). Counting is a
 * single atomic Lua script (INCR, plus EXPIRE on the first hit of a window). Redis failures fail open.
 */
class RateLimitFilter extends OncePerRequestFilter {

    static final String LIMIT_HEADER = "X-RateLimit-Limit";
    static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final String KEY_PREFIX = "bm4a:rl:w:";
    private static final long WARN_INTERVAL_MILLIS = 60_000;

    private static final RedisScript<Long> INCREMENT = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """, Long.class);

    private final int limit;
    private final long windowSeconds;
    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final Counter errors;
    private final Counter rejected;
    private final AtomicLong lastWarnAt = new AtomicLong(Long.MIN_VALUE);

    RateLimitFilter(RateLimitProperties properties, StringRedisTemplate redis, MeterRegistry meterRegistry,
            JsonMapper jsonMapper, Clock clock) {
        this.limit = properties.writesPerMinute();
        this.windowSeconds = Math.max(1, properties.window().toSeconds());
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.errors = Counter.builder("bm4a.ratelimit.errors")
                .description("Rate limit checks that failed (request let through)")
                .register(meterRegistry);
        this.rejected = Counter.builder("bm4a.ratelimit.rejected")
                .description("Mutating requests rejected with 429")
                .register(meterRegistry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !MUTATING_METHODS.contains(request.getMethod()) || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response);
            return;
        }

        long now = clock.instant().getEpochSecond();
        long window = now / windowSeconds;
        response.setHeader(LIMIT_HEADER, Integer.toString(limit));

        Long count;
        try {
            count = redis.execute(INCREMENT, List.of(key(token.getToken(), window)),
                    Long.toString(windowSeconds * 2));
        } catch (RuntimeException ex) {
            onRedisFailure(ex);
            chain.doFilter(request, response);
            return;
        }
        if (count == null) {
            onRedisFailure(new IllegalStateException("Rate limit script returned no value"));
            chain.doFilter(request, response);
            return;
        }

        response.setHeader(REMAINING_HEADER, Long.toString(Math.max(0, limit - count)));
        if (count > limit) {
            rejected.increment();
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString((window + 1) * windowSeconds - now));
            ProblemResponses.write(response,
                    Problems.of(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many write requests; retry later"),
                    jsonMapper);
            return;
        }
        chain.doFilter(request, response);
    }

    private static String key(Jwt jwt, long window) {
        String identity = jwt.getClaimAsString(JwtClaimNames.ISS) + "|" + jwt.getSubject();
        return KEY_PREFIX + sha256Hex(identity) + ":" + window;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void onRedisFailure(RuntimeException ex) {
        errors.increment();
        long nowMillis = clock.millis();
        long last = lastWarnAt.get();
        if ((last == Long.MIN_VALUE || nowMillis - last >= WARN_INTERVAL_MILLIS)
                && lastWarnAt.compareAndSet(last, nowMillis)) {
            log.warn("Rate limiting unavailable, letting requests through: {}", ex.toString());
        }
    }
}
