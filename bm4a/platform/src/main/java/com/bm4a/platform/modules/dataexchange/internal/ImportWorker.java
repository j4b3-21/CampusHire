package com.bm4a.platform.modules.dataexchange.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.bm4a.platform.modules.shared.persistence.SqlValues;

/**
 * Polls for queued import jobs and runs up to {@code workers} of them at a time, each on its own virtual thread.
 * Claiming uses {@code FOR UPDATE SKIP LOCKED}, so any number of API replicas can poll the same table. Every poll
 * also recovers running jobs whose heartbeat is older than {@code staleAfter} (their worker died).
 */
@Component
@EnableConfigurationProperties(DataExchangeProperties.class)
class ImportWorker implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ImportWorker.class);

    private final ImportJobRepository jobs;
    private final ImportRunner runner;
    private final DataExchangeProperties properties;
    private final Clock clock;
    private final Semaphore slots;
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("import-", 0).factory());

    ImportWorker(ImportJobRepository jobs, ImportRunner runner, DataExchangeProperties properties, Clock clock) {
        this.jobs = jobs;
        this.runner = runner;
        this.properties = properties;
        this.clock = clock;
        this.slots = new Semaphore(properties.workers());
    }

    @Scheduled(fixedDelayString = "${bm4a.dataexchange.poll-interval:2s}",
            initialDelayString = "${bm4a.dataexchange.poll-interval:2s}")
    void poll() {
        Instant now = SqlValues.now(clock);
        int recovered = jobs.recoverStale(now.minus(properties.staleAfter()), now);
        if (recovered > 0) {
            log.warn("Recovered {} import job(s) whose worker stopped", recovered);
        }
        while (slots.tryAcquire()) {
            Optional<ImportJob> job;
            try {
                job = jobs.claimNext(SqlValues.now(clock));
            } catch (RuntimeException ex) {
                slots.release();
                throw ex;
            }
            if (job.isEmpty()) {
                slots.release();
                return;
            }
            ImportJob claimed = job.get();
            executor.execute(() -> {
                try {
                    runner.run(claimed);
                } finally {
                    slots.release();
                }
            });
        }
    }

    /** Running jobs are interrupted on shutdown; stale recovery takes care of them. */
    @Override
    public void destroy() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
    }
}
