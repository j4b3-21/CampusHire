package com.bm4a.platform.modules.shared.resilience;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal circuit breaker for optional infrastructure (Redis): after a failure, calls are skipped for
 * {@code openDuration}; then a single caller probes while the others keep skipping. Callers log only when
 * {@link #recordFailure()} or {@link #recordSuccess()} report a state change, which rate-limits warnings naturally.
 */
public final class CircuitBreaker {

    private static final long CLOSED = Long.MIN_VALUE;

    private final long openMillis;
    private final Clock clock;
    private final AtomicLong openUntil = new AtomicLong(CLOSED);

    public CircuitBreaker(Duration openDuration, Clock clock) {
        this.openMillis = openDuration.toMillis();
        this.clock = clock;
    }

    /** Whether the protected call should be attempted now. */
    public boolean allowRequest() {
        long until = openUntil.get();
        if (until == CLOSED) {
            return true;
        }
        long now = clock.millis();
        return now >= until && openUntil.compareAndSet(until, now + openMillis);
    }

    /** @return {@code true} if the breaker was open and is now closed */
    public boolean recordSuccess() {
        return openUntil.getAndSet(CLOSED) != CLOSED;
    }

    /** @return {@code true} if the breaker was closed and is now open */
    public boolean recordFailure() {
        return openUntil.getAndSet(clock.millis() + openMillis) == CLOSED;
    }

    public boolean isOpen() {
        return openUntil.get() != CLOSED;
    }
}
