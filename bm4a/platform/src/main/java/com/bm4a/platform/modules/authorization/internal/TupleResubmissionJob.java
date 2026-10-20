package com.bm4a.platform.modules.authorization.internal;

import java.time.Duration;

import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.bm4a.platform.modules.authorization.RelationshipTuplesChanged;

/**
 * Retries tuple synchronizations that failed (for example while OpenFGA was down), plus publications stuck in
 * progress (for example after a crash between commit and synchronization).
 */
@Component
class TupleResubmissionJob {

    private static final Duration STUCK_AFTER = Duration.ofMinutes(5);

    private final FailedEventPublications failed;
    private final IncompleteEventPublications incomplete;

    TupleResubmissionJob(FailedEventPublications failed, IncompleteEventPublications incomplete) {
        this.failed = failed;
        this.incomplete = incomplete;
    }

    @Scheduled(fixedDelayString = "${bm4a.authz.openfga.resubmit-interval:30s}",
            initialDelayString = "${bm4a.authz.openfga.resubmit-interval:30s}")
    void resubmit() {
        failed.resubmit(ResubmissionOptions.defaults().withFilter(TupleResubmissionJob::isTupleChange));
        incomplete.resubmitIncompletePublications(ResubmissionOptions.defaults()
                .withMinAge(STUCK_AFTER)
                .withFilter(TupleResubmissionJob::isTupleChange));
    }

    private static boolean isTupleChange(EventPublication publication) {
        return publication.getEvent() instanceof RelationshipTuplesChanged;
    }
}
