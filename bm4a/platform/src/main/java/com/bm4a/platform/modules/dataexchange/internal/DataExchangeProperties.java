package com.bm4a.platform.modules.dataexchange.internal;

import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * @param maxRows data rows accepted per import file
 * @param maxFileSize largest import file
 * @param batchSize rows written per transaction
 * @param workers import jobs run concurrently per instance
 * @param staleAfter a running job without heartbeat for this long is recovered
 * @param timeZone zone of local date-times read from imports and written to exports
 *            ({@code DATA_EXCHANGE_TIME_ZONE})
 */
@Validated
@ConfigurationProperties("bm4a.dataexchange")
public record DataExchangeProperties(@DefaultValue("50000") @Min(1) int maxRows,
        @DefaultValue("25MB") @NotNull DataSize maxFileSize, @DefaultValue("500") @Min(1) @Max(5000) int batchSize,
        @DefaultValue("2") @Min(1) @Max(16) int workers, @DefaultValue("5m") @NotNull Duration staleAfter,
        @DefaultValue("UTC") @NotNull ZoneId timeZone) {
}
