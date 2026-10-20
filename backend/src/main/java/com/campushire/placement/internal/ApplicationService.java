package com.campushire.placement.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.bm4a.platform.modules.access.ModuleAccess;
import com.bm4a.platform.modules.access.ModuleGrant;
import com.bm4a.platform.modules.access.RecordAction;
import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.metadata.ModuleSchemas;
import com.bm4a.platform.modules.records.RecordOperations;
import com.bm4a.platform.modules.records.RecordReader;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.error.FieldViolation;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.users.CurrentUser;
import com.bm4a.platform.modules.workflow.Workflows;
import com.campushire.common.CampusMember;
import com.campushire.common.CampusMembers;
import com.campushire.common.CampusModules;
import com.campushire.common.RecordValues;
import com.campushire.placement.internal.EligibilityService.Verdict;
import com.campushire.placement.internal.PlacementRepository.Registration;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Applying to and withdrawing from drives (ADR-006 hot path). Applying is one short transaction without locks on the
 * drive: window and status checked with server time, eligibility re-evaluated from the student record, then
 * {@code INSERT ... ON CONFLICT DO NOTHING} into {@code campushire_drive_registration}; only the winner creates the
 * application record, every concurrent or repeated call returns it.
 */
@Service
public class ApplicationService {

    private final CampusMembers members;
    private final ModuleAccess access;
    private final ModuleSchemas schemas;
    private final RecordReader reader;
    private final RecordOperations records;
    private final Workflows workflows;
    private final EligibilityService eligibility;
    private final PlacementRepository repository;
    private final PlacementSettings settings;
    private final TransactionTemplate transactions;

    ApplicationService(CampusMembers members, ModuleAccess access, ModuleSchemas schemas, RecordReader reader,
            RecordOperations records, Workflows workflows, EligibilityService eligibility,
            PlacementRepository repository, PlacementSettings settings,
            PlatformTransactionManager transactionManager) {
        this.members = members;
        this.access = access;
        this.schemas = schemas;
        this.reader = reader;
        this.records = records;
        this.workflows = workflows;
        this.eligibility = eligibility;
        this.repository = repository;
        this.settings = settings;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** @param created {@code false} when the student had applied before (the existing application is returned) */
    public record ApplyResult(boolean created, ApplicationView application) {
    }

    public record ApplicationView(UUID id, UUID driveId, UUID studentId, String stage, long version,
            Instant createdAt) {
    }

    /**
     * Failures: drive not visible 404; not published 409 {@code drive_not_open}; outside the window 409
     * {@code registration_not_open} / {@code registration_closed}; ineligible 409 {@code not_eligible} with one
     * {@code errors[]} entry per failed rule ({@code field: "eligibility"}, {@code code}: reason code).
     */
    public ApplyResult apply(CurrentUser user, UUID organizationId, UUID driveId) {
        CampusMember member = members.require(user, organizationId);
        UUID studentId = member.requireStudentId();
        ModuleGrant driveGrant = access.require(member.context(), CampusModules.DRIVE, RecordAction.READ);
        TenantContext tenant = member.context();
        return transactions.execute(status -> {
            RecordView drive = reader.findActive(driveGrant, driveId)
                    .orElseThrow(() -> ApiException.notFound("Drive"));
            Optional<Registration> existing = repository.registration(organizationId, driveId, studentId);
            if (existing.isPresent()) {
                return new ApplyResult(false, existingApplication(organizationId, existing.get()));
            }
            requireOpen(drive, settings.now());
            ModuleSchema studentModule = schemas.find(organizationId, CampusModules.STUDENT)
                    .orElseThrow(() -> ApiException.notFound("Module"));
            RecordView student = reader.findActive(organizationId, studentModule.id(), studentId)
                    .orElseThrow(() -> ApiException.forbidden("student_not_linked",
                            "Your student record no longer exists; contact the placement cell"));
            Verdict verdict = eligibility.evaluate(EligibilityService.criteria(drive),
                    settings.policy(organizationId), eligibility.facts(organizationId, student),
                    eligibility.jobCtc(organizationId, drive));
            if (!verdict.eligible()) {
                throw new ApiException(HttpStatus.CONFLICT, "not_eligible", "You are not eligible for this drive",
                        verdict.reasons().stream()
                                .map(reason -> new FieldViolation("eligibility", reason.code(), reason.message()))
                                .toList());
            }
            if (!repository.register(organizationId, driveId, studentId, settings.now(), user.id())) {
                return new ApplyResult(false, existingApplication(organizationId,
                        repository.registration(organizationId, driveId, studentId).orElseThrow()));
            }
            ObjectNode data = JsonNodeFactory.instance.objectNode()
                    .put("drive", driveId.toString())
                    .put("student", studentId.toString());
            putId(data, "company", RecordValues.uuid(drive.data(), "company"));
            putId(data, "department", RecordValues.uuid(student.data(), "department"));
            RecordView application = records.create(tenant, CampusModules.APPLICATION, data, user.id());
            repository.attachApplication(organizationId, driveId, studentId, application.id());
            return new ApplyResult(true, view(application));
        });
    }

    /**
     * Withdraws the caller's application through the workflow's {@code withdraw} transition (from {@code applied}
     * or {@code shortlisted}); 404 without an application, 409 {@code transition_not_allowed} otherwise.
     */
    public ApplicationView withdraw(CurrentUser user, UUID organizationId, UUID driveId, String comment) {
        CampusMember member = members.require(user, organizationId);
        UUID studentId = member.requireStudentId();
        UUID applicationId = repository.registration(organizationId, driveId, studentId)
                .map(Registration::applicationId)
                .or(() -> repository.applicationsOfStudent(organizationId, studentId, List.of(driveId))
                        .values().stream().findFirst())
                .orElseThrow(() -> ApiException.notFound("Application"));
        RecordView application = workflows.transition(member.context(), CampusModules.APPLICATION, applicationId,
                "withdrawn", comment).record();
        return view(application);
    }

    private void requireOpen(RecordView drive, Instant now) {
        String status = DriveLifecycleService.currentStatus(drive);
        if (!"published".equals(status)) {
            throw ApiException.conflict("drive_not_open", "This drive is not accepting applications");
        }
        Instant opens = RecordValues.instant(drive.data(), "registration_opens_at");
        Instant closes = RecordValues.instant(drive.data(), "registration_closes_at");
        if (opens != null && now.isBefore(opens)) {
            throw ApiException.conflict("registration_not_open", "Registration opens at " + opens);
        }
        if (closes == null || !now.isBefore(closes)) {
            throw ApiException.conflict("registration_closed", "Registration for this drive has closed");
        }
    }

    private ApplicationView existingApplication(UUID organizationId, Registration registration) {
        List<RecordView> found = registration.applicationId() == null ? List.of()
                : reader.findActive(organizationId, List.of(registration.applicationId()));
        return found.stream()
                .findFirst()
                .map(ApplicationService::view)
                .orElseThrow(() -> ApiException.conflict("application_removed",
                        "Your application to this drive was removed by the placement cell"));
    }

    static ApplicationView view(RecordView application) {
        String stage = RecordValues.text(application.data(), "stage");
        return new ApplicationView(application.id(), RecordValues.uuid(application.data(), "drive"),
                RecordValues.uuid(application.data(), "student"), stage == null ? "applied" : stage,
                application.version(), application.createdAt());
    }

    private static void putId(ObjectNode data, String key, UUID value) {
        if (value != null) {
            data.put(key, value.toString());
        }
    }
}
