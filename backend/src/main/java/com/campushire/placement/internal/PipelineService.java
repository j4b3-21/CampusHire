package com.campushire.placement.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.bm4a.platform.modules.access.ModuleAccess;
import com.bm4a.platform.modules.access.ModuleGrant;
import com.bm4a.platform.modules.access.RecordAction;
import com.bm4a.platform.modules.records.RecordReader;
import com.bm4a.platform.modules.records.RecordView;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.users.CurrentUser;
import com.bm4a.platform.modules.workflow.WorkflowDefinition;
import com.bm4a.platform.modules.workflow.Workflows;
import com.bm4a.platform.modules.workflow.Workflows.TransitionOutcome;
import com.campushire.common.CampusMember;
import com.campushire.common.CampusMembers;
import com.campushire.common.CampusModules;
import com.campushire.common.RecordValues;
import com.campushire.placement.internal.PlacementRepository.RollMatch;

/**
 * Moves many applicants of one drive through the application workflow, selected by application ID or roll number.
 * Each application runs in its own transaction on behalf of the caller (their UPDATE scope, field policy and the
 * workflow's role rules apply), so one failure never undoes the others.
 */
@Service
public class PipelineService {

    static final int MAX_ITEMS = 2_000;
    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    private final CampusMembers members;
    private final ModuleAccess access;
    private final RecordReader reader;
    private final Workflows workflows;
    private final PlacementRepository repository;

    PipelineService(CampusMembers members, ModuleAccess access, RecordReader reader, Workflows workflows,
            PlacementRepository repository) {
        this.members = members;
        this.access = access;
        this.reader = reader;
        this.workflows = workflows;
        this.repository = repository;
    }

    public record ItemError(String code, String message) {
    }

    /** @param rollNumber set when the item was selected by roll number */
    public record ItemResult(UUID applicationId, String rollNumber, boolean ok, String stage, Long version,
            ItemError error) {
    }

    public record BulkResult(List<ItemResult> results, int succeeded, int failed, List<String> unknownRollNumbers) {
    }

    /**
     * Failures of the whole request: neither IDs nor roll numbers, or more than {@value #MAX_ITEMS} 400
     * {@code validation_failed}; unknown target state 400; drive not readable 404; no UPDATE on applications 403.
     */
    public BulkResult transition(CurrentUser user, UUID organizationId, UUID driveId, List<UUID> applicationIds,
            List<String> rollNumbers, String to, String comment) {
        CampusMember member = members.require(user, organizationId);
        if (!member.staff() && !member.recruiter()) {
            throw ApiException.forbidden("access_denied", "Only placement staff and recruiters can move applicants");
        }
        boolean noIds = applicationIds == null || applicationIds.isEmpty();
        boolean noRolls = rollNumbers == null || rollNumbers.isEmpty();
        if (noIds && noRolls) {
            throw ApiException.validation("applicationIds", "required", "applicationIds or rollNumbers is required");
        }
        int requested = (noIds ? 0 : applicationIds.size()) + (noRolls ? 0 : rollNumbers.size());
        if (requested > MAX_ITEMS) {
            throw ApiException.validation("applicationIds", "size", "at most " + MAX_ITEMS + " applicants at once");
        }
        ModuleGrant driveGrant = access.require(member.context(), CampusModules.DRIVE, RecordAction.READ);
        reader.findActive(driveGrant, driveId).orElseThrow(() -> ApiException.notFound("Drive"));
        WorkflowDefinition workflow = workflows.find(organizationId, CampusModules.APPLICATION)
                .orElseThrow(() -> ApiException.notFound("Workflow"));
        if (to == null || !workflow.hasState(to)) {
            throw ApiException.validation("to", "unknown_state", "to must be one of the application stages");
        }
        ModuleGrant grant = access.require(member.context(), CampusModules.APPLICATION, RecordAction.UPDATE);

        Map<UUID, String> targets = new LinkedHashMap<>();
        if (!noIds) {
            applicationIds.stream().filter(Objects::nonNull).forEach(id -> targets.putIfAbsent(id, null));
        }
        List<String> unknown = new ArrayList<>();
        if (!noRolls) {
            Map<String, String> requestedRolls = new LinkedHashMap<>();
            rollNumbers.stream().filter(Objects::nonNull).map(String::strip).filter(roll -> !roll.isEmpty())
                    .forEach(roll -> requestedRolls.putIfAbsent(roll.toLowerCase(Locale.ROOT), roll));
            Set<String> matched = new LinkedHashSet<>();
            for (RollMatch match : repository.applicationsByRollNumber(organizationId, driveId,
                    requestedRolls.keySet())) {
                matched.add(match.rollNumber());
                targets.putIfAbsent(match.applicationId(), requestedRolls.get(match.rollNumber()));
            }
            requestedRolls.forEach((key, original) -> {
                if (!matched.contains(key)) {
                    unknown.add(original);
                }
            });
        }

        List<ItemResult> results = new ArrayList<>();
        targets.forEach((applicationId, roll) -> results.add(transitionOne(grant, driveId, applicationId, roll, to,
                comment)));
        int succeeded = (int) results.stream().filter(ItemResult::ok).count();
        return new BulkResult(results, succeeded, results.size() - succeeded, unknown);
    }

    public BulkResult upload(CurrentUser user, UUID organizationId, UUID driveId, byte[] sheet, String to,
            String comment) {
        List<String> rolls = RollNumberSheets.read(sheet);
        return transition(user, organizationId, driveId, null, rolls, to, comment);
    }

    private ItemResult transitionOne(ModuleGrant grant, UUID driveId, UUID applicationId, String roll, String to,
            String comment) {
        try {
            RecordView application = reader.findActive(grant, applicationId)
                    .filter(record -> driveId.equals(RecordValues.uuid(record.data(), "drive")))
                    .orElseThrow(() -> ApiException.notFound("Application"));
            TransitionOutcome outcome = workflows.transition(grant, application.id(), null, to, comment);
            return new ItemResult(applicationId, roll, true, to, outcome.record().version(), null);
        } catch (ApiException ex) {
            return new ItemResult(applicationId, roll, false, null, null, new ItemError(ex.code(), ex.getMessage()));
        } catch (RuntimeException ex) {
            log.error("Transition of application {} failed", applicationId, ex);
            return new ItemResult(applicationId, roll, false, null, null,
                    new ItemError("internal_error", "An unexpected error occurred"));
        }
    }
}
