package com.campushire.projections.internal;

import java.time.Duration;

import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retries CampusHire event listeners (projections, notifications, membership sync) whose publications failed, and
 * publications stuck in progress after a crash. Every CampusHire listener is idempotent, so replays are safe.
 */
@Component
class CampusHireEventResubmission {

    private static final Duration STUCK_AFTER = Duration.ofMinutes(5);
    private static final String LISTENER_PREFIX = "com.campushire.";

    private final FailedEventPublications failed;
    private final IncompleteEventPublications incomplete;

    CampusHireEventResubmission(FailedEventPublications failed, IncompleteEventPublications incomplete) {
        this.failed = failed;
        this.incomplete = incomplete;
    }

    @Scheduled(fixedDelayString = "${campushire.events.resubmit-interval:60s}",
            initialDelayString = "${campushire.events.resubmit-interval:60s}")
    void resubmit() {
        failed.resubmit(ResubmissionOptions.defaults().withFilter(CampusHireEventResubmission::ours));
        incomplete.resubmitIncompletePublications(ResubmissionOptions.defaults()
                .withMinAge(STUCK_AFTER)
                .withFilter(CampusHireEventResubmission::ours));
    }

    private static boolean ours(EventPublication publication) {
        return publication instanceof TargetEventPublication target
                && target.getTargetIdentifier().getValue().startsWith(LISTENER_PREFIX);
    }
}
