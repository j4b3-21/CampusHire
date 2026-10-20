package com.bm4a.platform.modules.notifications.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties("bm4a.notifications")
record NotificationProperties(@Valid @NotNull @DefaultValue Email email, @Valid @NotNull @DefaultValue Stream stream) {

    /**
     * @param enabled whether the worker sends mail; without an SMTP host nothing is sent either way and rows stay
     *        {@code PENDING}
     * @param from sender address ({@code MAIL_FROM}); defaults to the SMTP user name if it is an address
     * @param pollInterval delay between worker runs
     * @param batchSize rows claimed per run
     * @param maxAttempts attempts before a row becomes {@code FAILED}
     * @param initialBackoff delay after the first failed attempt, doubled per attempt up to {@code maxBackoff}
     */
    record Email(@DefaultValue("true") boolean enabled, String from, @DefaultValue("5s") @NotNull Duration pollInterval,
            @DefaultValue("50") @Min(1) @Max(500) int batchSize, @DefaultValue("8") @Min(1) @Max(50) int maxAttempts,
            @DefaultValue("1m") @NotNull Duration initialBackoff, @DefaultValue("1h") @NotNull Duration maxBackoff,
            @Valid @NotNull @DefaultValue Smtp smtp) {
    }

    /**
     * @param ssl implicit TLS (port 465); otherwise {@code starttls} upgrades a plain connection (port 587)
     * @param timeout connect, read and write timeout of one SMTP connection
     */
    record Smtp(String host, @DefaultValue("587") @Min(1) @Max(65535) int port, String username, String password,
            @DefaultValue("true") boolean starttls, @DefaultValue("false") boolean ssl,
            @DefaultValue("10s") @NotNull Duration timeout) {

        boolean configured() {
            return host != null && !host.isBlank();
        }
    }

    /**
     * @param redisEnabled fan out new notifications to other replicas through Redis pub/sub
     * @param maxConnectionsPerUser open streams per user on one replica; the oldest is closed beyond it
     * @param timeout lifetime of one stream; clients reconnect
     * @param heartbeat interval of keep-alive comments, below proxy idle timeouts (Nginx: 60 s)
     */
    record Stream(@DefaultValue("true") boolean redisEnabled,
            @DefaultValue("5") @Min(1) @Max(50) int maxConnectionsPerUser,
            @DefaultValue("30m") @NotNull Duration timeout, @DefaultValue("25s") @NotNull Duration heartbeat) {
    }
}
