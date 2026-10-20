package com.campushire.placement.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.bm4a.platform.modules.access.ModuleAccess;
import com.bm4a.platform.modules.access.ModuleGrant;
import com.bm4a.platform.modules.access.RecordAction;
import com.bm4a.platform.modules.records.RecordReader;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.records.RecordWriter;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.error.FieldViolation;
import com.bm4a.platform.modules.users.CurrentUser;
import com.campushire.common.CampusModules;
import com.campushire.common.RecordValues;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Drive lifecycle {@code draft -> published -> registration_closed -> in_progress -> completed}, or
 * {@code cancelled} from any unfinished state. Requires UPDATE on drives (placement officers). Publishing validates
 * the eligibility rules and the registration window and notifies every eligible linked student; cancelling notifies
 * the applicants.
 */
@Service
public class DriveLifecycleService {

    static final int NOTIFICATION_BATCH = 500;

    public enum Action {
        PUBLISH("published", Set.of("draft")),
        CLOSE("registration_closed", Set.of("published")),
        START("in_progress", Set.of("published", "registration_closed")),
        COMPLETE("completed", Set.of("registration_closed", "in_progress")),
        CANCEL("cancelled", Set.of("draft", "published", "registration_closed", "in_progress"));

        final String target;
        final Set<String> from;

        Action(String target, Set<String> from) {
            this.target = target;
            this.from = from;
        }
    }

    /** @param notifiedStudents linked student accounts notified by this change */
    public record DriveState(UUID id, long version, String title, String driveStatus, Instant registrationOpensAt,
            Instant registrationClosesAt, int notifiedStudents) {
    }

    private final ModuleAccess access;
    private final RecordReader reader;
    private final RecordWriter writer;
    private final EligibilityService eligibility;
    private final PlacementRepository repository;
    private final PlacementSettings settings;
    private final PlacementNotifications notifications;
    private final TransactionTemplate transactions;

    DriveLifecycleService(ModuleAccess access, RecordReader reader, RecordWriter writer,
            EligibilityService eligibility, PlacementRepository repository, PlacementSettings settings,
            PlacementNotifications notifications, PlatformTransactionManager transactionManager) {
        this.access = access;
        this.reader = reader;
        this.writer = writer;
        this.eligibility = eligibility;
        this.repository = repository;
        this.settings = settings;
        this.notifications = notifications;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Failures: drive not readable 404; status does not allow the action 409 {@code drive_status_conflict};
     * {@code expectedVersion} stale 412; publishing with invalid rules or window 400 {@code validation_failed}.
     */
    public DriveState change(CurrentUser user, UUID organizationId, UUID driveId, Long expectedVersion,
            Action action) {
        ModuleGrant grant = access.require(user, organizationId, CampusModules.DRIVE, RecordAction.UPDATE);
        return transactions.execute(status -> {
            RecordView drive = reader.findActive(grant, driveId).orElseThrow(() -> ApiException.notFound("Drive"));
            String current = currentStatus(drive);
            if (!action.from.contains(current)) {
                throw ApiException.conflict("drive_status_conflict", "A " + current.replace('_', ' ')
                        + " drive cannot be " + action.target.replace('_', ' '));
            }
            ObjectNode patch = JsonNodeFactory.instance.objectNode().put("drive_status", action.target);
            EligibilityCriteria criteria = null;
            if (action == Action.PUBLISH) {
                criteria = validateForPublishing(drive);
                if (drive.data().has("eligibility") && !drive.data().get("eligibility").isNull()) {
                    patch.set("eligibility", criteria.toJson());
                }
            }
            RecordView updated = writer.patch(grant, driveId,
                    expectedVersion == null ? drive.version() : expectedVersion, patch);
            String title = RecordValues.text(updated.data(), "title");
            int notified = 0;
            if (action == Action.PUBLISH) {
                BigDecimal ctc = eligibility.jobCtc(organizationId, updated);
                List<UUID> recipients = repository.eligibleLinkedUsers(EligibilityQuery.of(organizationId, criteria,
                        settings.policy(organizationId), ctc, null, null));
                inBatches(recipients, batch -> notifications.drivePublished(organizationId, driveId, title, batch));
                notified = recipients.size();
            } else if (action == Action.CANCEL) {
                List<UUID> recipients = repository.linkedUsers(organizationId,
                        repository.applicantStudentIds(organizationId, driveId));
                inBatches(recipients, batch -> notifications.driveCancelled(organizationId, driveId, title, batch));
                notified = recipients.size();
            }
            return new DriveState(updated.id(), updated.version(), title, currentStatus(updated),
                    RecordValues.instant(updated.data(), "registration_opens_at"),
                    RecordValues.instant(updated.data(), "registration_closes_at"), notified);
        });
    }

    private EligibilityCriteria validateForPublishing(RecordView drive) {
        EligibilityCriteria criteria = EligibilityService.criteria(drive);
        Instant opens = RecordValues.instant(drive.data(), "registration_opens_at");
        Instant closes = RecordValues.instant(drive.data(), "registration_closes_at");
        List<FieldViolation> violations = new ArrayList<>();
        if (closes == null) {
            violations.add(new FieldViolation("registration_closes_at", "required",
                    "a registration deadline is required to publish"));
        } else if (!closes.isAfter(settings.now())) {
            violations.add(new FieldViolation("registration_closes_at", "past",
                    "the registration deadline must be in the future"));
        } else if (opens != null && !opens.isBefore(closes)) {
            violations.add(new FieldViolation("registration_opens_at", "order",
                    "registration must open before it closes"));
        }
        if (RecordValues.uuid(drive.data(), "job") == null) {
            violations.add(new FieldViolation("job", "required", "a job is required to publish"));
        }
        if (!violations.isEmpty()) {
            throw ApiException.validation(violations);
        }
        return criteria;
    }

    static String currentStatus(RecordView drive) {
        String status = RecordValues.text(drive.data(), "drive_status");
        return status == null ? "draft" : status;
    }

    static void inBatches(List<UUID> recipients, Consumer<Collection<UUID>> send) {
        for (int i = 0; i < recipients.size(); i += NOTIFICATION_BATCH) {
            send.accept(recipients.subList(i, Math.min(recipients.size(), i + NOTIFICATION_BATCH)));
        }
    }
}
