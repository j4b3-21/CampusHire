package com.campushire.placement.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.bm4a.platform.modules.access.ModuleAccess;
import com.bm4a.platform.modules.access.ModuleGrant;
import com.bm4a.platform.modules.access.RecordAction;
import com.bm4a.platform.modules.records.RecordReader;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.shared.persistence.SqlValues;
import com.bm4a.platform.modules.users.CurrentUser;
import com.campushire.common.CampusMember;
import com.campushire.common.CampusMembers;
import com.campushire.common.CampusModules;
import com.campushire.common.RecordValues;
import com.campushire.placement.internal.PlacementRepository.OfferAggregate;

/**
 * Eligibility of students for drives: single verdicts through {@link EligibilityEvaluator}, whole-institute previews
 * through {@link EligibilityQuery}.
 */
@Service
public class EligibilityService {

    static final int DEFAULT_SAMPLE = 10;
    static final int MAX_SAMPLE = 50;

    private final CampusMembers members;
    private final ModuleAccess access;
    private final RecordReader reader;
    private final PlacementRepository repository;
    private final PlacementSettings settings;

    EligibilityService(CampusMembers members, ModuleAccess access, RecordReader reader,
            PlacementRepository repository, PlacementSettings settings) {
        this.members = members;
        this.access = access;
        this.reader = reader;
        this.repository = repository;
        this.settings = settings;
    }

    public record Reason(String code, String message) {
    }

    public record Verdict(boolean eligible, List<Reason> reasons) {
    }

    public record SampleStudent(UUID studentId, String rollNumber, String fullName, UUID departmentId,
            BigDecimal cgpa) {
    }

    public record DepartmentCount(UUID departmentId, long eligible, long considered) {
    }

    public record Breakdown(List<DepartmentCount> byDepartment) {
    }

    /** @param reasons students failing each rule (a student can fail several) */
    public record Preview(UUID driveId, long eligibleCount, long totalConsidered, List<SampleStudent> sample,
            Breakdown breakdown, Map<String, Long> reasons, BigDecimal driveCtc) {
    }

    /**
     * Placement staff; coordinators only see their departments' students. The drive must be readable (404).
     * Invalid eligibility rules are 400 {@code validation_failed}.
     */
    public Preview preview(CurrentUser user, UUID organizationId, UUID driveId, Integer sampleSize) {
        CampusMember member = members.require(user, organizationId).requireStaff();
        ModuleGrant grant = access.require(member.context(), CampusModules.DRIVE, RecordAction.READ);
        RecordView drive = reader.findActive(grant, driveId).orElseThrow(() -> ApiException.notFound("Drive"));
        int limit = sampleSize == null ? DEFAULT_SAMPLE : Math.max(0, Math.min(MAX_SAMPLE, sampleSize));
        EligibilityCriteria criteria = criteria(drive);
        BigDecimal ctc = jobCtc(organizationId, drive);
        EligibilityQuery query = EligibilityQuery.of(organizationId, criteria, settings.policy(organizationId), ctc,
                member.coordinator() ? member.departmentIds() : null, null);

        StringBuilder counts = new StringBuilder();
        EligibilityQuery.REASONS.forEach(code -> counts.append(", count(*) FILTER (WHERE r_").append(code)
                .append(") AS ").append(code));
        Map<String, Long> reasons = new LinkedHashMap<>();
        EligibilityQuery.REASONS.forEach(code -> reasons.put(code, 0L));
        long[] totals = new long[2];
        List<DepartmentCount> departments = new ArrayList<>();
        repository.jdbc().sql(query.cte() + "SELECT department_id, GROUPING(department_id) AS total_row,"
                + " count(*) AS considered, count(*) FILTER (WHERE eligible) AS eligible" + counts
                + " FROM verdicts GROUP BY GROUPING SETS ((), (department_id))"
                + " ORDER BY total_row DESC, department_id NULLS LAST")
                .params(query.params())
                .query(rs -> {
                    if (rs.getInt("total_row") == 1) {
                        totals[0] = rs.getLong("eligible");
                        totals[1] = rs.getLong("considered");
                        for (String code : EligibilityQuery.REASONS) {
                            reasons.put(code, rs.getLong(code));
                        }
                    } else {
                        departments.add(new DepartmentCount(SqlValues.uuid(rs, "department_id"),
                                rs.getLong("eligible"), rs.getLong("considered")));
                    }
                });
        List<SampleStudent> sample = limit == 0 ? List.of() : repository.jdbc().sql(query.cte() + """
                SELECT student_id, roll_number, full_name, department_id, cgpa FROM verdicts
                WHERE eligible ORDER BY roll_number, student_id LIMIT :sampleLimit
                """)
                .params(query.params()).param("sampleLimit", limit)
                .query((rs, n) -> new SampleStudent(SqlValues.uuid(rs, "student_id"), rs.getString("roll_number"),
                        rs.getString("full_name"), SqlValues.uuid(rs, "department_id"), rs.getBigDecimal("cgpa")))
                .list();
        return new Preview(driveId, totals[0], totals[1], sample, new Breakdown(departments), reasons, ctc);
    }

    /** Reason codes per student, as evaluated in SQL; students without failed rules map to an empty set. */
    Map<UUID, Set<String>> evaluateInSql(EligibilityQuery query) {
        Map<UUID, Set<String>> result = new HashMap<>();
        repository.jdbc().sql(query.cte() + "SELECT * FROM verdicts")
                .params(query.params())
                .query(rs -> {
                    Set<String> failed = new LinkedHashSet<>();
                    for (String code : EligibilityQuery.REASONS) {
                        if (rs.getBoolean("r_" + code)) {
                            failed.add(code);
                        }
                    }
                    result.put(SqlValues.uuid(rs, "student_id"), failed);
                });
        return result;
    }

    /** The drive's eligibility rules (400 {@code validation_failed} when malformed). */
    static EligibilityCriteria criteria(RecordView drive) {
        return EligibilityCriteria.parse(drive.data().get("eligibility"));
    }

    /** CTC of the drive's job record, {@code null} without a job or CTC. */
    BigDecimal jobCtc(UUID organizationId, RecordView drive) {
        UUID jobId = RecordValues.uuid(drive.data(), "job");
        if (jobId == null) {
            return null;
        }
        return reader.findActive(organizationId, List.of(jobId)).stream().findFirst()
                .map(job -> RecordValues.amount(job.data(), "ctc"))
                .orElse(null);
    }

    /** Facts from the authoritative student record plus the offer projection. */
    StudentFacts facts(UUID organizationId, RecordView student) {
        OfferAggregate offers = repository.offers(organizationId, student.id());
        Integer backlogs = RecordValues.integer(student.data(), "active_backlogs");
        return new StudentFacts(student.id(), RecordValues.decimal(student.data(), "cgpa"),
                backlogs == null ? 0 : backlogs, RecordValues.uuid(student.data(), "department"),
                RecordValues.uuid(student.data(), "batch"), RecordValues.text(student.data(), "program"),
                RecordValues.decimal(student.data(), "tenth_percentage"),
                RecordValues.decimal(student.data(), "twelfth_percentage"),
                RecordValues.text(student.data(), "placement_status"), offers.activeOffers(), offers.highestCtc());
    }

    /** Facts from the projections, falling back to the record when the student was not projected yet. */
    StudentFacts facts(UUID organizationId, UUID studentModuleId, UUID studentId) {
        return repository.studentFacts(organizationId, studentId)
                .or(() -> reader.findActive(organizationId, studentModuleId, studentId)
                        .map(student -> facts(organizationId, student)))
                .orElseThrow(() -> ApiException.forbidden("student_not_linked",
                        "Your student record no longer exists; contact the placement cell"));
    }

    Verdict evaluate(EligibilityCriteria criteria, PlacementPolicy policy, StudentFacts facts, BigDecimal driveCtc) {
        EligibilityEvaluator.Verdict verdict = EligibilityEvaluator.evaluate(criteria, policy, facts, driveCtc);
        return new Verdict(verdict.eligible(),
                verdict.reasons().stream().map(reason -> new Reason(reason.code(), reason.message())).toList());
    }

    /** Like {@link #evaluate}, but rules that cannot be parsed make the drive closed to everyone. */
    Verdict evaluate(RecordView drive, PlacementPolicy policy, StudentFacts facts, BigDecimal driveCtc) {
        try {
            return evaluate(criteria(drive), policy, facts, driveCtc);
        } catch (ApiException ex) {
            return new Verdict(false, List.of(new Reason("eligibility_invalid",
                    "The placement cell is updating this drive's eligibility rules")));
        }
    }

    Map<UUID, StudentFacts> facts(UUID organizationId, Collection<UUID> studentIds) {
        return repository.studentFacts(organizationId, studentIds);
    }
}
