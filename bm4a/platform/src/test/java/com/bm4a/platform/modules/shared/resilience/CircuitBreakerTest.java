package com.bm4a.platform.modules.shared.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

    final MutableClock clock = new MutableClock();
    final CircuitBreaker breaker = new CircuitBreaker(Duration.ofSeconds(5), clock);

    @Test
    void opensOnFailureAndLetsOneProbeThroughAfterTheOpenPeriod() {
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.recordFailure()).isTrue();
        assertThat(breaker.recordFailure()).isFalse();
        assertThat(breaker.isOpen()).isTrue();
        assertThat(breaker.allowRequest()).isFalse();

        clock.advance(Duration.ofSeconds(5));
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.allowRequest()).isFalse();

        assertThat(breaker.recordSuccess()).isTrue();
        assertThat(breaker.recordSuccess()).isFalse();
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.isOpen()).isFalse();
    }

    @Test
    void aFailedProbeKeepsItOpen() {
        breaker.recordFailure();
        clock.advance(Duration.ofSeconds(6));
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.recordFailure()).isFalse();
        clock.advance(Duration.ofSeconds(4));
        assertThat(breaker.allowRequest()).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(breaker.allowRequest()).isTrue();
    }

    static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-20T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
