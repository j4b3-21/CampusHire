package com.campushire.projections.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refreshes {@code campushire_placement_stats} concurrently (readers are never blocked). A transaction-scoped
 * PostgreSQL advisory lock lets exactly one API replica refresh per round; the others skip it.
 */
@Component
public class PlacementStatsRefresher {

    /** Arbitrary, stable key of the advisory lock ("CHSTATS"). */
    static final long LOCK_KEY = 0x4348535441545300L;
    private static final Logger log = LoggerFactory.getLogger(PlacementStatsRefresher.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    PlacementStatsRefresher(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${campushire.projections.stats-refresh-interval:5m}",
            initialDelayString = "${campushire.projections.stats-refresh-interval:5m}")
    void scheduledRefresh() {
        try {
            refresh();
        } catch (RuntimeException ex) {
            log.warn("Refreshing campushire_placement_stats failed; retrying next round: {}", ex.toString());
        }
    }

    /** Returns {@code false} when another replica is refreshing right now. */
    public boolean refresh() {
        Boolean refreshed = transactions.execute(status -> {
            Boolean locked = jdbc.sql("SELECT pg_try_advisory_xact_lock(:key)").param("key", LOCK_KEY)
                    .query(Boolean.class).single();
            if (!Boolean.TRUE.equals(locked)) {
                return false;
            }
            jdbc.sql("REFRESH MATERIALIZED VIEW CONCURRENTLY campushire_placement_stats").update();
            return true;
        });
        return Boolean.TRUE.equals(refreshed);
    }
}
