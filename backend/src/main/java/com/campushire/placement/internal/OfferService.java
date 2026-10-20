package com.campushire.placement.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.bm4a.platform.modules.access.ModuleAccess;
import com.bm4a.platform.modules.access.ModuleGrant;
import com.bm4a.platform.modules.access.RecordAction;
import com.bm4a.platform.modules.access.RecordScope;
import com.bm4a.platform.modules.audit.AuditEntry;
import com.bm4a.platform.modules.audit.AuditLog;
import com.bm4a.platform.modules.records.RecordOperations;
import com.bm4a.platform.modules.records.RecordReader;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.error.FieldViolation;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.users.CurrentUser;
import com.bm4a.platform.modules.workflow.Workflows;
import com.campushire.common.CampusMember;
import com.campushire.common.CampusMembers;
import com.campushire.common.CampusModules;
import com.campushire.common.RecordValues;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Offers and the institute's offer policy. Creating an offer copies student, company, drive and department from the
 * application, moves the application to {@code offered} and marks an {@code unplaced} student {@code placed}.
 * Students accept or decline their own extended offers until {@code respond_by} (inclusive, institute time zone).
 * Accepting never declines other offers; declining the last active (extended or accepted) offer returns a
 * {@code placed} student to {@code unplaced}.
 */
@Service
public class OfferService {

    static final String CURRENCY = "INR";
    private static final Set<String> ACTIVE = Set.of("extended", "accepted");
    private static final Set<String> BEYOND_OFFERED = Set.of("offered", "joined");

    private final CampusMembers members;
    private final ModuleAccess access;
    private final RecordReader reader;
    private final RecordOperations records;
    private final Workflows workflows;
    private final PlacementSettings settings;
    private final PlacementRepository repository;
    private final PlacementNotifications notifications;
    private final AuditLog audit;
    private final TransactionTemplate transactions;

    OfferService(CampusMembers members, ModuleAccess access, RecordReader reader, RecordOperations records,
            Workflows workflows, PlacementSettings settings, PlacementRepository repository,
            PlacementNotifications notifications, AuditLog audit, PlatformTransactionManager transactionManager) {
        this.members = members;
        this.access = access;
        this.reader = reader;
        this.records = records;
        this.workflows = workflows;
        this.settings = settings;
        this.repository = repository;
        this.notifications = notifications;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** @param override admins only: record the offer although it violates the offer policy (audited) */
    public record NewOffer(UUID applicationId, BigDecimal ctc, String offerType, LocalDate joiningDate,
            LocalDate respondBy, String offerLetterFileId, boolean override) {
    }

    public record OfferView(UUID id, long version, UUID applicationId, UUID studentId, UUID companyId, UUID driveId,
            UUID departmentId, BigDecimal ctc, String currency, String offerType, LocalDate joiningDate,
            LocalDate respondBy, String offerLetterFileId, String offerStatus, String applicationStage,
            String placementStatus, boolean policyOverridden) {
    }

    private record ActiveOffers(int count, BigDecimal highestCtc) {
    }

    /**
     * Requires CREATE on offers (placement officers). Failures: unknown application 404; application not yet
     * shortlisted 409 {@code transition_not_allowed}; offer exists for the application 409 {@code unique_violation};
     * policy violated 409 {@code offer_policy_violation} (admins may pass {@code override: true}).
     */
    public OfferView create(CurrentUser user, UUID organizationId, NewOffer request) {
        ModuleGrant grant = access.require(user, organizationId, CampusModules.OFFER, RecordAction.CREATE);
        validate(request);
        TenantContext tenant = grant.tenant();
        return transactions.execute(status -> {
            RecordView application = records.get(tenant, CampusModules.APPLICATION, request.applicationId())
                    .orElseThrow(() -> ApiException.notFound("Application"));
            UUID studentId = RecordValues.uuid(application.data(), "student");
            RecordView student = records.get(tenant, CampusModules.STUDENT, studentId)
                    .orElseThrow(() -> ApiException.notFound("Student"));
            PlacementPolicy policy = settings.policy(organizationId);
            ActiveOffers active = activeOffers(tenant, studentId, null);
            boolean dreamUpgrade = policy.allowDreamUpgrade()
                    && request.ctc().compareTo(policy.dreamCtcThreshold()) >= 0
                    && (active.highestCtc() == null || request.ctc().compareTo(active.highestCtc()) > 0);
            boolean violation = active.count() >= policy.maxOffersPerStudent() && !dreamUpgrade;
            if (violation && !(request.override() && grant.admin())) {
                throw ApiException.conflict("offer_policy_violation", "The student already holds " + active.count()
                        + " active offer(s) (limit " + policy.maxOffersPerStudent() + ") and this offer is not a "
                        + "dream upgrade" + (grant.admin() ? "; pass override to record it anyway" : ""));
            }

            ObjectNode data = JsonNodeFactory.instance.objectNode()
                    .put("application", application.id().toString())
                    .put("student", studentId.toString());
            copyId(application.data(), data, "company");
            copyId(application.data(), data, "drive");
            copyId(application.data(), data, "department");
            data.putObject("ctc").put("amount", request.ctc().toPlainString()).put("currency", CURRENCY);
            putText(data, "offer_type", request.offerType());
            putText(data, "joining_date", request.joiningDate() == null ? null : request.joiningDate().toString());
            putText(data, "respond_by", request.respondBy() == null ? null : request.respondBy().toString());
            if (request.offerLetterFileId() != null && !request.offerLetterFileId().isBlank()) {
                data.putObject("offer_letter").put("fileId", request.offerLetterFileId().strip());
            }
            RecordView offer = records.create(tenant, CampusModules.OFFER, data, student.ownerUserId());

            String stage = RecordValues.text(application.data(), "stage");
            if (!BEYOND_OFFERED.contains(stage)) {
                stage = RecordValues.text(workflows.transition(tenant, CampusModules.APPLICATION, application.id(),
                        "offered", "Offer recorded").record().data(), "stage");
            }
            String placement = RecordValues.text(student.data(), "placement_status");
            if (placement == null || "unplaced".equals(placement)) {
                placement = setPlacement(tenant, studentId, "placed");
            }
            if (violation) {
                audit.record(AuditEntry.of(organizationId, user.id(), "campushire.offer.policy_overridden", "record",
                        offer.id()).with("student", studentId.toString()).with("activeOffers", active.count())
                        .with("maxOffersPerStudent", policy.maxOffersPerStudent()));
            }
            repository.linkedUsers(organizationId, List.of(studentId)).forEach(userId ->
                    notifications.offerExtended(organizationId, offer.id(), companyName(organizationId, offer),
                            userId));
            return view(offer, stage, placement, violation);
        });
    }

    public OfferView accept(CurrentUser user, UUID organizationId, UUID offerId) {
        return respond(user, organizationId, offerId, "accepted");
    }

    public OfferView decline(CurrentUser user, UUID organizationId, UUID offerId) {
        return respond(user, organizationId, offerId, "declined");
    }

    /**
     * The student owning the offer (attribute {@code student}); others 404. Failures: not extended 409
     * {@code offer_not_pending}; past {@code respond_by} 409 {@code offer_expired}.
     */
    private OfferView respond(CurrentUser user, UUID organizationId, UUID offerId, String decision) {
        CampusMember member = members.require(user, organizationId);
        UUID studentId = member.requireStudentId();
        ModuleGrant grant = access.require(member.context(), CampusModules.OFFER, RecordAction.READ);
        TenantContext tenant = member.context();
        return transactions.execute(status -> {
            RecordView visible = reader.findActive(grant, offerId)
                    .filter(offer -> studentId.equals(RecordValues.uuid(offer.data(), "student")))
                    .orElseThrow(() -> ApiException.notFound("Offer"));
            if (!"extended".equals(RecordValues.text(visible.data(), "offer_status"))) {
                throw ApiException.conflict("offer_not_pending", "This offer is no longer awaiting your response");
            }
            LocalDate respondBy = RecordValues.date(visible.data(), "respond_by");
            if (respondBy != null && settings.today().isAfter(respondBy)) {
                throw ApiException.conflict("offer_expired", "The deadline to respond to this offer has passed");
            }
            RecordView offer = records.patch(tenant, CampusModules.OFFER, offerId, visible.version(),
                    JsonNodeFactory.instance.objectNode().put("offer_status", decision));
            RecordView student = records.get(tenant, CampusModules.STUDENT, studentId)
                    .orElseThrow(() -> ApiException.notFound("Student"));
            String placement = RecordValues.text(student.data(), "placement_status");
            if ("accepted".equals(decision) && (placement == null || "unplaced".equals(placement))) {
                placement = setPlacement(tenant, studentId, "placed");
            } else if ("declined".equals(decision) && "placed".equals(placement)
                    && activeOffers(tenant, studentId, offerId).count() == 0) {
                placement = setPlacement(tenant, studentId, "unplaced");
            }
            String stage = RecordValues.uuid(offer.data(), "application") == null ? null
                    : records.get(tenant, CampusModules.APPLICATION, RecordValues.uuid(offer.data(), "application"))
                            .map(application -> RecordValues.text(application.data(), "stage")).orElse(null);
            return view(offer, stage, placement, false);
        });
    }

    /** Active offers of a student from the records (exact, unlike the projection), optionally excluding one. */
    private ActiveOffers activeOffers(TenantContext tenant, UUID studentId, UUID excluded) {
        int count = 0;
        BigDecimal highest = null;
        int page = 0;
        int pages;
        do {
            var result = records.list(tenant, CampusModules.OFFER,
                    Map.of("filter[student]", List.of(studentId.toString()),
                            "filter[offer_status][in]", List.of(String.join(",", ACTIVE))),
                    null, null, RecordScope.all(), new PageQuery(page++, PageQuery.MAX_SIZE));
            for (RecordView offer : result.items()) {
                if (offer.id().equals(excluded)) {
                    continue;
                }
                count++;
                BigDecimal ctc = RecordValues.amount(offer.data(), "ctc");
                if (ctc != null && (highest == null || ctc.compareTo(highest) > 0)) {
                    highest = ctc;
                }
            }
            pages = result.totalPages();
        } while (page < pages);
        return new ActiveOffers(count, highest);
    }

    private String setPlacement(TenantContext tenant, UUID studentId, String placement) {
        records.patch(tenant, CampusModules.STUDENT, studentId, null,
                JsonNodeFactory.instance.objectNode().put("placement_status", placement));
        return placement;
    }

    private String companyName(UUID organizationId, RecordView offer) {
        UUID companyId = RecordValues.uuid(offer.data(), "company");
        return companyId == null ? null : reader.findActive(organizationId, List.of(companyId)).stream().findFirst()
                .map(company -> RecordValues.text(company.data(), "name")).orElse(null);
    }

    private static void validate(NewOffer request) {
        if (request.applicationId() == null) {
            throw ApiException.validation("applicationId", "required", "applicationId is required");
        }
        if (request.ctc() == null || request.ctc().signum() <= 0) {
            throw ApiException.validation(List.of(new FieldViolation("ctc", "range", "ctc must be a positive amount")));
        }
    }

    private static OfferView view(RecordView offer, String applicationStage, String placementStatus,
            boolean overridden) {
        ObjectNode data = offer.data();
        return new OfferView(offer.id(), offer.version(), RecordValues.uuid(data, "application"),
                RecordValues.uuid(data, "student"), RecordValues.uuid(data, "company"),
                RecordValues.uuid(data, "drive"), RecordValues.uuid(data, "department"),
                RecordValues.amount(data, "ctc"), CURRENCY, RecordValues.text(data, "offer_type"),
                RecordValues.date(data, "joining_date"), RecordValues.date(data, "respond_by"),
                RecordValues.fileId(data, "offer_letter"), RecordValues.text(data, "offer_status"), applicationStage,
                placementStatus, overridden);
    }

    private static void copyId(ObjectNode from, ObjectNode to, String key) {
        UUID value = RecordValues.uuid(from, key);
        if (value != null) {
            to.put(key, value.toString());
        }
    }

    private static void putText(ObjectNode data, String key, String value) {
        if (value != null && !value.isBlank()) {
            data.put(key, value.strip());
        }
    }
}
