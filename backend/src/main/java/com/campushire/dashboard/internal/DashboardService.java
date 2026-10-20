package com.campushire.dashboard.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.bm4a.platform.modules.access.AccessSummary;
import com.bm4a.platform.modules.access.AccessSummary.ModuleCapabilities;
import com.bm4a.platform.modules.access.ModuleAccess;
import com.bm4a.platform.modules.access.RecordAction;
import com.bm4a.platform.modules.access.RecordScope;
import com.bm4a.platform.modules.metadata.FieldConfig;
import com.bm4a.platform.modules.metadata.FieldSchema;
import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.metadata.ModuleSchemas;
import com.bm4a.platform.modules.records.RecordOperations;
import com.bm4a.platform.modules.records.RecordReader;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.bm4a.platform.modules.shared.web.PageQuery;
import com.bm4a.platform.modules.shared.web.PageResponse;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.users.CurrentUser;
import com.campushire.common.CampusMember;
import com.campushire.common.CampusMembers;
import com.campushire.common.CampusModules;
import com.campushire.common.RecordValues;
import com.campushire.dashboard.internal.DashboardRepository.DriveRow;
import com.campushire.dashboard.internal.DashboardRepository.PlacementTotals;
import com.campushire.placement.PlacementInsights;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the dashboard of the caller's persona. Staff figures come from the projections (seconds behind the records)
 * and placement percentages from {@code campushire_placement_stats} (refreshed every few minutes).
 */
@Service
public class DashboardService {

    static final int UPCOMING_DRIVES = 5;
    static final int FOLLOW_UP_ITEMS = 10;
    static final int RECRUITER_DRIVES = 20;

    private final CampusMembers members;
    private final ModuleAccess access;
    private final ModuleSchemas schemas;
    private final RecordReader reader;
    private final RecordOperations records;
    private final PlacementInsights insights;
    private final DashboardRepository repository;
    private final DashboardCache cache;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final ZoneId zone;

    DashboardService(CampusMembers members, ModuleAccess access, ModuleSchemas schemas, RecordReader reader,
            RecordOperations records, PlacementInsights insights, DashboardRepository repository,
            DashboardCache cache, JsonMapper jsonMapper, Clock clock,
            @Value("${campushire.time-zone:Asia/Kolkata}") ZoneId zone) {
        this.members = members;
        this.access = access;
        this.schemas = schemas;
        this.reader = reader;
        this.records = records;
        this.insights = insights;
        this.repository = repository;
        this.cache = cache;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.zone = zone;
    }

    public record Counts(long students, long openDrives, long applicationsThisWeek, long activeOffers) {
    }

    /** @param placementPercentage placed among participating students, two decimals; {@code null} without any */
    public record Placement(long totalStudents, long participatingStudents, long placedStudents,
            BigDecimal placementPercentage, long offers, BigDecimal highestCtc, BigDecimal averageCtc,
            Instant refreshedAt) {
    }

    public record UpcomingDrive(UUID id, String title, UUID companyId, String companyName, String driveStatus,
            LocalDate driveDate, Instant registrationClosesAt) {
    }

    public record FollowUp(UUID id, String title, UUID companyId, LocalDate dueDate, boolean overdue) {
    }

    public record FollowUps(long dueToday, long overdue, List<FollowUp> items) {
    }

    /** @param departmentIds the coordinator's departments, {@code null} for institute-wide staff */
    public record StaffDashboard(String role, List<UUID> departmentIds, Counts counts,
            Map<String, Long> companiesByStage, Placement placement, List<UpcomingDrive> upcomingDrives,
            FollowUps followUps, Instant generatedAt) {
    }

    /** @param completeness percentage of filled profile fields the student maintains, {@code missingFields} the rest */
    public record Profile(int completeness, List<String> missingFields) {
    }

    public record StudentOffer(UUID id, UUID companyId, String companyName, BigDecimal ctc, String offerType,
            String offerStatus, LocalDate respondBy) {
    }

    public record StudentInfo(UUID id, String rollNumber, String fullName, String placementStatus,
            boolean profileVerified) {
    }

    public record StudentDashboard(String role, StudentInfo student, Profile profile, long eligibleOpenDrives,
            Map<String, Long> applicationsByStage, List<StudentOffer> offers, Instant generatedAt) {
    }

    public record RecruiterDrive(UUID id, String title, UUID companyId, String driveStatus, LocalDate driveDate,
            Instant registrationClosesAt, long applicants, Map<String, Long> applicantsByStage) {
    }

    public record RecruiterDashboard(String role, List<UUID> companyIds, List<RecruiterDrive> drives,
            Instant generatedAt) {
    }

    public record MemberDashboard(String role, Instant generatedAt) {
    }

    public JsonNode dashboard(CurrentUser user, UUID organizationId) {
        CampusMember member = members.require(user, organizationId);
        if (member.officer()) {
            return cache.get(organizationId + ":staff", () -> tree(staff(member, null)));
        }
        if (member.coordinator()) {
            List<UUID> departments = member.departmentIds().stream().sorted().toList();
            return cache.get(organizationId + ":coordinator:" + departments.hashCode() + ":" + departments,
                    () -> tree(staff(member, departments)));
        }
        if (member.student()) {
            return tree(student(user, member));
        }
        if (member.recruiter()) {
            return tree(recruiter(member));
        }
        return tree(new MemberDashboard(member.roleKey(), SqlValues.now(clock)));
    }

    private StaffDashboard staff(CampusMember member, List<UUID> departments) {
        UUID org = member.organizationId();
        Instant now = SqlValues.now(clock);
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Counts counts = new Counts(repository.students(org, departments), repository.openDrives(org, now),
                repository.applicationsSince(org, departments, now.minus(Duration.ofDays(7))),
                repository.activeOffers(org, departments));
        PlacementTotals totals = repository.placement(org, departments);
        Placement placement = new Placement(totals.totalStudents(), totals.participatingStudents(),
                totals.placedStudents(), percentage(totals.placedStudents(), totals.participatingStudents()),
                totals.offers(), totals.highestCtc(),
                totals.averageCtc() == null ? null : totals.averageCtc().setScale(2, RoundingMode.HALF_UP),
                totals.refreshedAt());
        List<DriveRow> drives = repository.upcomingDrives(org, today, now, UPCOMING_DRIVES);
        Map<UUID, String> companies = names(org, drives.stream().map(DriveRow::companyId).toList());
        List<UpcomingDrive> upcoming = drives.stream().map(drive -> new UpcomingDrive(drive.id(), drive.title(),
                drive.companyId(), companies.get(drive.companyId()), drive.status(), drive.driveDate(),
                drive.registrationClosesAt())).toList();
        String role = member.admin() ? (member.tpo() ? "tpo" : "admin") : member.roleKey();
        return new StaffDashboard(role, departments, counts, companiesByStage(member.context()), placement, upcoming,
                followUps(member.context(), today), now);
    }

    private Map<String, Long> companiesByStage(TenantContext tenant) {
        Map<String, Long> result = new LinkedHashMap<>();
        ModuleSchema companies = schemas.find(tenant.organizationId(), CampusModules.COMPANY).orElse(null);
        FieldSchema stage = companies == null ? null : companies.field("pipeline_stage")
                .filter(FieldSchema::visible).orElse(null);
        if (stage == null || stage.config() == null) {
            return result;
        }
        RecordScope scope = access.find(tenant, CampusModules.COMPANY, RecordAction.READ)
                .filter(grant -> !grant.fields().hidden("pipeline_stage"))
                .map(grant -> grant.scope()).orElse(null);
        if (scope == null) {
            return result;
        }
        for (FieldConfig.SelectOption option : stage.config().optionsOrEmpty()) {
            PageResponse<RecordView> page = records.list(tenant, CampusModules.COMPANY,
                    Map.of("filter[pipeline_stage]", List.of(option.value())), null, null, scope, new PageQuery(0, 1));
            result.put(option.value(), page.totalElements());
        }
        return result;
    }

    /** Open follow-ups due today or earlier; {@code null} for callers who cannot read follow-ups. */
    private FollowUps followUps(TenantContext tenant, LocalDate today) {
        RecordScope scope = access.find(tenant, CampusModules.FOLLOW_UP, RecordAction.READ)
                .map(grant -> grant.scope()).orElse(null);
        if (scope == null) {
            return null;
        }
        String date = today.toString();
        PageResponse<RecordView> due = records.list(tenant, CampusModules.FOLLOW_UP,
                Map.of("filter[follow_up_status]", List.of("open"), "filter[due_date][lte]", List.of(date)),
                "due_date", null, scope, new PageQuery(0, FOLLOW_UP_ITEMS));
        long dueToday = records.list(tenant, CampusModules.FOLLOW_UP,
                Map.of("filter[follow_up_status]", List.of("open"), "filter[due_date]", List.of(date)),
                null, null, scope, new PageQuery(0, 1)).totalElements();
        List<FollowUp> items = due.items().stream().map(record -> {
            LocalDate dueDate = RecordValues.date(record.data(), "due_date");
            return new FollowUp(record.id(), RecordValues.text(record.data(), "title"),
                    RecordValues.uuid(record.data(), "company"), dueDate,
                    dueDate != null && dueDate.isBefore(today));
        }).toList();
        return new FollowUps(dueToday, due.totalElements() - dueToday, items);
    }

    private StudentDashboard student(CurrentUser user, CampusMember member) {
        UUID org = member.organizationId();
        UUID studentId = member.requireStudentId();
        ModuleSchema module = schemas.find(org, CampusModules.STUDENT).orElse(null);
        RecordView record = module == null ? null : reader.findActive(org, module.id(), studentId).orElse(null);
        StudentInfo info = record == null ? null : new StudentInfo(record.id(),
                RecordValues.text(record.data(), "roll_number"), RecordValues.text(record.data(), "full_name"),
                RecordValues.text(record.data(), "placement_status"),
                RecordValues.bool(record.data(), "profile_verified"));
        Profile profile = record == null ? new Profile(0, List.of())
                : profile(access.describe(user, org), module, record);
        List<DashboardRepository.OfferRow> offerRows = repository.studentOffers(org, studentId);
        Map<UUID, String> companies = names(org, offerRows.stream().map(DashboardRepository.OfferRow::companyId)
                .toList());
        List<StudentOffer> offers = offerRows.stream().map(offer -> new StudentOffer(offer.id(), offer.companyId(),
                companies.get(offer.companyId()), offer.ctc(), offer.offerType(), offer.status(), offer.respondBy()))
                .toList();
        return new StudentDashboard("student", info, profile,
                record == null ? 0 : insights.eligibleOpenDrives(org, studentId),
                repository.studentApplicationsByStage(org, studentId), offers, SqlValues.now(clock));
    }

    /**
     * Completeness over the optional fields the student maintains: visible, not required, and neither hidden from
     * nor read-only for the student's role (phone, skills, links, resume, photo, ...).
     */
    static Profile profile(AccessSummary summary, ModuleSchema module, RecordView record) {
        ModuleCapabilities capabilities = summary.modules().get(CampusModules.STUDENT);
        Set<String> excluded = new LinkedHashSet<>();
        if (capabilities != null) {
            excluded.addAll(capabilities.hiddenFields());
            excluded.addAll(capabilities.readOnlyFields());
        }
        List<String> fields = module.fields().stream()
                .filter(FieldSchema::visible).filter(field -> !field.required())
                .map(FieldSchema::apiKey).filter(key -> !excluded.contains(key))
                .toList();
        if (fields.isEmpty()) {
            return new Profile(100, List.of());
        }
        List<String> missing = fields.stream().filter(key -> !RecordValues.present(record.data(), key)).toList();
        int filled = fields.size() - missing.size();
        return new Profile((int) Math.round(filled * 100.0 / fields.size()), missing);
    }

    private RecruiterDashboard recruiter(CampusMember member) {
        UUID org = member.organizationId();
        List<UUID> companies = member.companyIds();
        List<DriveRow> drives = repository.companyDrives(org, companies, RECRUITER_DRIVES);
        Map<UUID, Map<String, Long>> stages = repository.applicantsByStage(org,
                drives.stream().map(DriveRow::id).toList(), companies);
        List<RecruiterDrive> result = new ArrayList<>();
        for (DriveRow drive : drives) {
            Map<String, Long> byStage = stages.getOrDefault(drive.id(), Map.of());
            result.add(new RecruiterDrive(drive.id(), drive.title(), drive.companyId(), drive.status(),
                    drive.driveDate(), drive.registrationClosesAt(),
                    byStage.values().stream().mapToLong(Long::longValue).sum(), byStage));
        }
        return new RecruiterDashboard("recruiter", companies, result, SqlValues.now(clock));
    }

    private Map<UUID, String> names(UUID organizationId, Collection<UUID> companyIds) {
        Set<UUID> ids = new LinkedHashSet<>(companyIds);
        ids.remove(null);
        Map<UUID, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            reader.findActive(organizationId, ids).forEach(company ->
                    names.put(company.id(), RecordValues.text(company.data(), "name")));
        }
        return names;
    }

    private JsonNode tree(Object payload) {
        return jsonMapper.valueToTree(payload);
    }

    static BigDecimal percentage(long part, long whole) {
        return whole == 0 ? null
                : BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(whole), 2, RoundingMode.HALF_UP);
    }
}
