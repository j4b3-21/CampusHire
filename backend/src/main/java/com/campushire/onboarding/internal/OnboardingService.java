package com.campushire.onboarding.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bm4a.platform.modules.audit.AuditEntry;
import com.bm4a.platform.modules.audit.AuditLog;
import com.bm4a.platform.modules.metadata.ModuleSchema;
import com.bm4a.platform.modules.metadata.ModuleSchemas;
import com.bm4a.platform.modules.records.RecordOperations;
import com.bm4a.platform.modules.records.RecordReader;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.tenancy.MembershipAdministration;
import com.bm4a.platform.modules.tenancy.MembershipAdministration.MembershipView;
import com.bm4a.platform.modules.tenancy.MembershipRole;
import com.bm4a.platform.modules.tenancy.OrganizationSettings;
import com.bm4a.platform.modules.tenancy.TenantAccess;
import com.bm4a.platform.modules.users.CurrentUser;
import com.campushire.common.CampusModules;
import com.campushire.common.CampusRoles;
import com.campushire.common.RecordValues;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Students link themselves to their student record: the caller's verified e-mail must belong to one of the institute's
 * allowed domains and equal (case-insensitively) the {@code email} of an active student record. Claiming grants a
 * {@code student} membership carrying the record, department and batch as attributes and makes the caller the
 * record's owner. Claims are idempotent and serialized per user and organization.
 */
@Service
public class OnboardingService {

    private final OnboardingRepository repository;
    private final OrganizationSettings settings;
    private final MembershipAdministration memberships;
    private final TenantAccess tenants;
    private final ModuleSchemas schemas;
    private final RecordReader reader;
    private final RecordOperations records;
    private final AuditLog audit;

    OnboardingService(OnboardingRepository repository, OrganizationSettings settings,
            MembershipAdministration memberships, TenantAccess tenants, ModuleSchemas schemas, RecordReader reader,
            RecordOperations records, AuditLog audit) {
        this.repository = repository;
        this.settings = settings;
        this.memberships = memberships;
        this.tenants = tenants;
        this.schemas = schemas;
        this.reader = reader;
        this.records = records;
        this.audit = audit;
    }

    /**
     * @param member whether the caller is an active member
     * @param linked whether the caller is a student member linked to a student record
     * @param studentRecordFound whether a student record uses the caller's e-mail
     */
    public record Institute(UUID organizationId, String name, boolean member, String roleKey, boolean linked,
            boolean studentRecordFound) {
    }

    public record Discovery(String email, boolean emailVerified, String domain, List<Institute> organizations) {
    }

    /** @param alreadyLinked whether the caller had claimed the record before */
    public record ClaimResult(UUID organizationId, UUID studentId, String roleKey, Map<String, Object> attributes,
            boolean alreadyLinked) {
    }

    @Transactional(readOnly = true)
    public Discovery discover(CurrentUser user) {
        String email = normalizedEmail(user);
        String domain = domain(email);
        if (domain == null) {
            return new Discovery(user.email(), user.emailVerified(), null, List.of());
        }
        List<Institute> institutes = new ArrayList<>();
        for (OnboardingRepository.InstituteRow row : repository.institutesForDomain(domain, email)) {
            Optional<MembershipView> membership = memberships.find(row.organizationId(), user.id())
                    .filter(MembershipView::active);
            boolean linked = membership.filter(view -> CampusRoles.STUDENT.equals(view.roleKey()))
                    .map(view -> view.attributes().get(CampusRoles.ATTR_STUDENT) != null).orElse(false);
            institutes.add(new Institute(row.organizationId(), row.name(), membership.isPresent(),
                    membership.map(MembershipView::roleKey).orElse(null), linked, row.studentFound()));
        }
        return new Discovery(user.email(), user.emailVerified(), domain, institutes);
    }

    /**
     * Failures: unverified e-mail 403 {@code email_not_verified}; e-mail domain not allowed by the institute 403
     * {@code domain_not_allowed}; no student record with the e-mail 404 {@code student_not_found}; revoked membership
     * 403 {@code membership_revoked}; member with another role 409 {@code already_member}; record already claimed by
     * another account 409 {@code student_already_claimed}.
     */
    @Transactional
    public ClaimResult claim(CurrentUser user, UUID organizationId) {
        if (!user.emailVerified()) {
            throw ApiException.forbidden("email_not_verified", "Verify your e-mail address before joining an institute");
        }
        String email = normalizedEmail(user);
        String domain = domain(email);
        if (domain == null || !allowedDomains(organizationId).contains(domain)) {
            throw ApiException.forbidden("domain_not_allowed",
                    "Your e-mail domain is not registered with this institute; sign in with your college account");
        }
        repository.lockClaim(organizationId, user.id());

        Optional<MembershipView> existing = memberships.find(organizationId, user.id());
        if (existing.isPresent() && !existing.get().active()) {
            throw ApiException.forbidden("membership_revoked",
                    "Your access to this institute was revoked; contact the placement cell");
        }
        if (existing.isPresent() && !CampusRoles.STUDENT.equals(existing.get().roleKey())) {
            throw ApiException.conflict("already_member", "You are already a member of this institute");
        }

        ModuleSchema studentModule = schemas.find(organizationId, CampusModules.STUDENT)
                .orElseThrow(OnboardingService::studentNotFound);
        RecordView student = findStudent(organizationId, studentModule, email, user.email()).orElseThrow(
                OnboardingService::studentNotFound);
        boolean alreadyLinked = existing.map(view -> student.id().toString()
                .equals(view.attributes().get(CampusRoles.ATTR_STUDENT))).orElse(false)
                && user.id().equals(student.ownerUserId());
        if (!user.id().equals(student.ownerUserId()) && claimedByAnother(organizationId, student)) {
            throw ApiException.conflict("student_already_claimed",
                    "This student record is linked to another account; contact the placement cell");
        }

        Map<String, Object> attributes = attributes(existing.map(MembershipView::attributes).orElse(Map.of()),
                student);
        memberships.grant(organizationId, user.id(), MembershipRole.MEMBER, CampusRoles.STUDENT, attributes,
                user.id());
        records.transferOwner(tenants.requireMembership(user.id(), organizationId).context(), CampusModules.STUDENT,
                student.id(), user.id());
        if (!alreadyLinked) {
            audit.record(AuditEntry.of(organizationId, user.id(), "campushire.student.claimed", "record", student.id())
                    .with("module", CampusModules.STUDENT));
        }
        return new ClaimResult(organizationId, student.id(), CampusRoles.STUDENT, attributes, alreadyLinked);
    }

    /** Membership attributes of a student member: the record and its department and batch, other keys kept. */
    static Map<String, Object> attributes(Map<String, Object> current, RecordView student) {
        Map<String, Object> attributes = new LinkedHashMap<>(current);
        attributes.put(CampusRoles.ATTR_STUDENT, student.id().toString());
        put(attributes, CampusRoles.ATTR_DEPARTMENT, RecordValues.uuid(student.data(), "department"));
        put(attributes, CampusRoles.ATTR_BATCH, RecordValues.uuid(student.data(), "batch"));
        return attributes;
    }

    private Optional<RecordView> findStudent(UUID organizationId, ModuleSchema module, String email,
            String rawEmail) {
        Optional<RecordView> projected = repository.studentIdByEmail(organizationId, email)
                .flatMap(id -> reader.findActive(organizationId, module.id(), id))
                .filter(record -> email.equalsIgnoreCase(RecordValues.text(record.data(), "email")));
        if (projected.isPresent()) {
            return projected;
        }
        // The projection may lag a just-imported record; the record engine matches the stored spelling exactly.
        for (String candidate : new LinkedHashSet<>(List.of(email, rawEmail.strip()))) {
            JsonNode value = JsonNodeFactory.instance.stringNode(candidate);
            Optional<RecordView> found = records.findActiveByField(organizationId, CampusModules.STUDENT, "email",
                    value);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private boolean claimedByAnother(UUID organizationId, RecordView student) {
        if (student.ownerUserId() == null) {
            return false;
        }
        return memberships.find(organizationId, student.ownerUserId())
                .filter(MembershipView::active)
                .filter(view -> CampusRoles.STUDENT.equals(view.roleKey()))
                .map(view -> student.id().toString().equals(view.attributes().get(CampusRoles.ATTR_STUDENT)))
                .orElse(false);
    }

    private List<String> allowedDomains(UUID organizationId) {
        JsonNode domains = settings.get(organizationId).path("campushire").path("allowedEmailDomains");
        List<String> result = new ArrayList<>();
        if (domains.isArray()) {
            domains.values().forEach(value -> {
                if (value.isString()) {
                    result.add(value.stringValue().strip().toLowerCase(Locale.ROOT));
                }
            });
        }
        return result;
    }

    private static void put(Map<String, Object> attributes, String key, UUID value) {
        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, value.toString());
        }
    }

    private static String normalizedEmail(CurrentUser user) {
        return user.email() == null || user.email().isBlank() ? null : user.email().strip().toLowerCase(Locale.ROOT);
    }

    private static String domain(String email) {
        if (email == null) {
            return null;
        }
        int at = email.lastIndexOf('@');
        return at < 1 || at == email.length() - 1 ? null : email.substring(at + 1);
    }

    private static ApiException studentNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "student_not_found",
                "No student record uses your e-mail address; contact the placement cell");
    }
}
