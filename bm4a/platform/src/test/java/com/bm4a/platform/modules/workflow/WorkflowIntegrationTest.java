package com.bm4a.platform.modules.workflow;

import static com.bm4a.platform.support.Fixtures.addMemberWithRole;
import static com.bm4a.platform.support.Fixtures.createModule;
import static com.bm4a.platform.support.Fixtures.createOrg;
import static com.bm4a.platform.support.Fixtures.createRecord;
import static com.bm4a.platform.support.Fixtures.createRole;
import static com.bm4a.platform.support.TestApi.etag;
import static com.bm4a.platform.support.TestApi.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.bm4a.platform.modules.records.RecordOperations;
import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.tenancy.MembershipRole;
import com.bm4a.platform.modules.tenancy.TenantContext;
import com.bm4a.platform.modules.workflow.Workflows.TransitionOutcome;
import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestApi;
import com.bm4a.platform.support.TestApi.Caller;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Workflow definitions, enforcement on every write path, history, available transitions, bulk transitions and
 * events (ADR-007).
 */
@IntegrationTest
@RecordApplicationEvents
class WorkflowIntegrationTest {

    static final String MODULE = """
            {"apiKey": "ticket", "labelSingular": "Ticket", "labelPlural": "Tickets",
             "settings": {"accessMode": "ROLES"}, "fields": [
              {"apiKey": "title", "label": "Title", "type": "TEXT", "required": true},
              {"apiKey": "team", "label": "Team", "type": "SINGLE_SELECT", "filterable": true,
               "config": {"options": [{"value": "blue", "label": "Blue"}, {"value": "red", "label": "Red"}]}},
              {"apiKey": "stage", "label": "Stage", "type": "SINGLE_SELECT", "filterable": true, "defaultValue": "new",
               "config": {"options": [{"value": "new", "label": "New"}, {"value": "review", "label": "In review"},
                 {"value": "approved", "label": "Approved"}, {"value": "rejected", "label": "Rejected"},
                 {"value": "on_hold", "label": "On hold"}]}}]}
            """;

    static final String DEFINITION = """
            {"initial": "new", "states": [
              {"key": "new", "label": "New", "category": "OPEN", "color": "slate"},
              {"key": "review", "label": "In review", "category": "IN_PROGRESS", "color": "blue"},
              {"key": "approved", "label": "Approved", "category": "WON", "color": "emerald"},
              {"key": "rejected", "label": "Rejected", "category": "LOST", "color": "red"}],
             "transitions": [
              {"key": "submit", "label": "Submit", "from": ["new"], "to": "review", "roles": ["*"]},
              {"key": "approve", "label": "Approve", "from": ["review"], "to": "approved", "roles": ["manager"]},
              {"key": "reject", "label": "Reject", "from": ["new", "review"], "to": "rejected",
               "roles": ["manager", "agent"]},
              {"key": "reopen", "label": "Reopen", "from": ["rejected"], "to": "new", "roles": ["manager"],
               "requireComment": true}]}
            """;

    static final String WORKFLOW = "/api/v1/orgs/{org}/modules/ticket/workflow";
    static final String RECORD = "/api/v1/orgs/{org}/modules/ticket/records/{id}";

    @Autowired
    MockMvc mvc;

    @Autowired
    Workflows workflows;

    @Autowired
    RecordOperations operations;

    @Autowired
    ApplicationEvents events;

    TestApi api;
    Caller owner;
    Caller blueAgent;
    Caller redAgent;
    Caller manager;
    Caller viewer;
    String org;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc);
        owner = api.newUser();
        blueAgent = api.newUser();
        redAgent = api.newUser();
        manager = api.newUser();
        viewer = api.newUser();
        org = createOrg(owner, "Workflow Co");
        createModule(owner, org, MODULE);
        createRole(owner, org, """
                {"apiKey": "agent", "label": "Agent", "permissions": {"modules": {"ticket": {"read": "ALL",
                  "create": true, "update": {"scope": "ATTRIBUTE", "field": "team", "attribute": "team"}}}}}
                """);
        createRole(owner, org, """
                {"apiKey": "manager", "label": "Manager", "permissions": {"modules": {"ticket": {"read": "ALL",
                  "create": true, "update": "ALL"}}}}
                """);
        createRole(owner, org, """
                {"apiKey": "viewer", "label": "Viewer", "permissions": {"modules": {"ticket": {"read": "ALL"}}}}
                """);
        addMemberWithRole(owner, org, blueAgent, "agent", "{\"team\": \"blue\"}");
        addMemberWithRole(owner, org, redAgent, "agent", "{\"team\": \"red\"}");
        addMemberWithRole(owner, org, manager, "manager", null);
        addMemberWithRole(owner, org, viewer, "viewer", null);
        owner.put(WORKFLOW, null, "{\"field\": \"stage\", \"definition\": " + DEFINITION + "}", org)
                .andExpect(status().isCreated());
    }

    @Test
    void definitionsAreValidatedAgainstTheFieldAndVersioned() throws Exception {
        owner.put(WORKFLOW, null, "{\"field\": \"title\", \"definition\": " + DEFINITION + "}", org)
                .andExpect(status().isPreconditionRequired());
        owner.get(WORKFLOW, org)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.module").value("ticket"))
                .andExpect(jsonPath("$.field").value("stage"))
                .andExpect(jsonPath("$.definition.initial").value("new"))
                .andExpect(jsonPath("$.definition.states[1].category").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.definition.states[1].color").value("blue"))
                .andExpect(jsonPath("$.definition.transitions[3].requireComment").value(true))
                .andExpect(jsonPath("$.definition.transitions[0].roles[0]").value("*"));
        viewer.get(WORKFLOW, org).andExpect(status().isOk());
        viewer.put(WORKFLOW, "\"1\"", "{\"field\": \"stage\", \"definition\": " + DEFINITION + "}", org)
                .andExpect(status().isForbidden());

        owner.put(WORKFLOW, "\"1\"", "{\"field\": \"title\", \"definition\": " + DEFINITION + "}", org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].code", hasItem("unsupported_type")));
        String invalid = """
                {"field": "stage", "definition": {"initial": "review", "extra": 1, "states": [
                  {"key": "new", "label": "New", "category": "OPEN"},
                  {"key": "review", "label": "Review", "category": "DONE"},
                  {"key": "bogus", "label": "Bogus", "category": "LOST"},
                  {"key": "new", "label": "Again", "category": "OPEN", "shape": "round"}],
                 "transitions": [
                  {"key": "go", "label": "Go", "from": ["new"], "to": "nowhere", "roles": ["*", "agent"]},
                  {"key": "go", "label": "Loop", "from": ["review"], "to": "review", "roles": ["Bad Role"]}]}}
                """;
        MvcResult rejected = owner.put(WORKFLOW, "\"1\"", invalid, org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andReturn();
        List<String> fields = read(rejected, "$.errors[*].field");
        assertThat(fields).contains("definition.extra", "definition.states[1].category", "definition.states[2].key",
                "definition.states[3].key", "definition.states[3].shape", "definition.transitions[0].to",
                "definition.transitions[0].roles", "definition.transitions[1].key", "definition.transitions[1].from",
                "definition.transitions[1].roles", "field");
        List<String> codes = read(rejected, "$.errors[*].code");
        assertThat(codes).contains("unknown_property", "unknown_option", "duplicate", "unknown_state",
                "self_transition", "default_mismatch");

        String changed = DEFINITION.replace("\"Submit\"", "\"Send for review\"");
        owner.put(WORKFLOW, "\"1\"", "{\"field\": \"stage\", \"definition\": " + changed + "}", org)
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.definition.transitions[0].label").value("Send for review"));
        owner.put(WORKFLOW, "\"1\"", "{\"field\": \"stage\", \"definition\": " + DEFINITION + "}", org)
                .andExpect(status().isPreconditionFailed());
        owner.get("/api/v1/orgs/{org}/audit-events?resourceType=workflow", org)
                .andExpect(jsonPath("$.items[*].action", hasItem("workflow.updated")));

        owner.delete(WORKFLOW, null, org).andExpect(status().isPreconditionRequired());
        owner.delete(WORKFLOW, "\"2\"", org).andExpect(status().isNoContent());
        owner.get(WORKFLOW, org).andExpect(status().isNotFound());
        String id = createRecord(owner, org, "ticket", "{\"title\": \"Free\", \"stage\": \"approved\"}");
        patch(owner, id, "{\"stage\": \"on_hold\"}").andExpect(status().isOk());
    }

    @Test
    void membersMayOnlyUseTransitionsListingTheirRole() throws Exception {
        String id = createRecord(blueAgent, org, "ticket", "{\"title\": \"T\", \"team\": \"blue\"}");
        blueAgent.post("/api/v1/orgs/{org}/modules/ticket/records",
                "{\"data\": {\"title\": \"Skip\", \"team\": \"blue\", \"stage\": \"review\"}}", org)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("transition_not_allowed"))
                .andExpect(jsonPath("$.detail", containsString("start in state 'new'")));

        patch(blueAgent, id, "{\"stage\": \"review\"}").andExpect(status().isOk());
        patch(blueAgent, id, "{\"stage\": \"approved\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("transition_not_allowed"))
                .andExpect(jsonPath("$.detail", containsString("Allowed from 'review': reject (to 'rejected')")));
        patch(blueAgent, id, "{\"stage\": null}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("transition_not_allowed"));
        patch(viewer, id, "{\"stage\": \"approved\"}").andExpect(status().isForbidden());
        patch(manager, id, "{\"stage\": \"approved\"}").andExpect(status().isOk());
        patch(manager, id, "{\"stage\": \"new\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("No transitions from 'approved'")));
        patch(manager, id, "{\"title\": \"Renamed\"}").andExpect(status().isOk());
    }

    @Test
    void adminsMayUseEveryDefinedTransitionButNoOther() throws Exception {
        String id = createRecord(owner, org, "ticket", "{\"title\": \"T\"}");
        patch(owner, id, "{\"stage\": \"approved\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("submit (to 'review'), reject (to 'rejected')")));
        patch(owner, id, "{\"stage\": \"review\"}").andExpect(status().isOk());
        patch(owner, id, "{\"stage\": \"approved\"}").andExpect(status().isOk());
        patch(owner, id, "{\"stage\": \"on_hold\"}").andExpect(status().isConflict());
    }

    @Test
    void trustedCodeFollowsDefinedTransitionsWithoutRoleChecks() throws Exception {
        String id = createRecord(blueAgent, org, "ticket", "{\"title\": \"T\", \"team\": \"blue\"}");
        UUID recordId = UUID.fromString(id);
        TenantContext agent = new TenantContext(UUID.fromString(org), UUID.fromString(blueAgent.id()),
                MembershipRole.MEMBER);
        workflows.transition(agent, "ticket", recordId, "review", null);
        TransitionOutcome approved = workflows.transition(agent, "ticket", recordId, "approved", "Checked by system");
        assertThat(approved.transition().transitionKey()).isEqualTo("approve");
        assertThat(approved.transition().fromState()).isEqualTo("review");
        assertThat(approved.transition().comment()).isEqualTo("Checked by system");
        assertThat(approved.record().data().get("stage").asString()).isEqualTo("approved");

        assertThatThrownBy(() -> workflows.transition(agent, "ticket", recordId, "new", null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("transition_not_allowed"));
        assertThatThrownBy(() -> workflows.transition(agent, "ticket", recordId, "approved", null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessage()).contains("already in state"));
        assertThatThrownBy(() -> workflows.transition(agent, "ticket", recordId, "nowhere", null))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("validation_failed"));
        assertThatThrownBy(() -> operations.patch(agent, "ticket", recordId, null, json("{\"stage\": \"rejected\"}")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("transition_not_allowed"));
        assertThatThrownBy(() -> operations.create(agent, "ticket", json("{\"title\": \"X\", \"stage\": \"review\"}"),
                null)).isInstanceOf(ApiException.class);
        assertThat(workflows.find(UUID.fromString(org), "ticket")).get()
                .satisfies(definition -> assertThat(definition.transitionsFrom("new"))
                        .extracting(WorkflowDefinition.Transition::key).containsExactly("submit", "reject"));
    }

    @Test
    void transitionsRequiringACommentGoThroughTheTransitionsEndpoint() throws Exception {
        String id = createRecord(owner, org, "ticket", "{\"title\": \"T\"}");
        patch(owner, id, "{\"stage\": \"rejected\"}").andExpect(status().isOk());
        patch(manager, id, "{\"stage\": \"new\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("comment_required"));
        String transitions = RECORD + "/transitions";
        manager.post(transitions, "{\"to\": \"new\"}", org, id).andExpect(status().isPreconditionRequired());
        transitionAs(manager, id, "\"2\"", "{\"to\": \"new\", \"comment\": \"  \"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("comment_required"));
        transitionAs(manager, id, "\"1\"", "{\"to\": \"new\", \"comment\": \"Second chance\"}")
                .andExpect(status().isPreconditionFailed());
        transitionAs(blueAgent, id, "\"2\"", "{\"to\": \"new\", \"comment\": \"Please\"}")
                .andExpect(status().isNotFound());
        transitionAs(manager, id, "\"2\"", "{\"to\": \"new\", \"comment\": \"Second chance\"}")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.record.version").value(3))
                .andExpect(jsonPath("$.record.data.stage").value("new"))
                .andExpect(jsonPath("$.transition.transitionKey").value("reopen"))
                .andExpect(jsonPath("$.transition.fromState").value("rejected"))
                .andExpect(jsonPath("$.transition.comment").value("Second chance"))
                .andExpect(jsonPath("$.transition.recordVersion").value(3));
        transitionAs(manager, id, "\"3\"", "{\"to\": \"bogus\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("to"));
        transitionAs(manager, id, "\"3\"", "{\"to\": \"new\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("already in state")));
        transitionAs(manager, id, "\"3\"", "{\"to\": \"review\", \"unknown\": true}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void everyChangeOfTheWorkflowFieldLeavesExactlyOneHistoryEntry() throws Exception {
        String id = createRecord(owner, org, "ticket", "{\"title\": \"T\", \"team\": \"blue\"}");
        patch(blueAgent, id, "{\"stage\": \"review\"}").andExpect(status().isOk());
        patch(blueAgent, id, "{\"title\": \"No state change\"}").andExpect(status().isOk());
        transitionAs(blueAgent, id, "\"3\"", "{\"to\": \"rejected\", \"comment\": \"Duplicate\"}")
                .andExpect(status().isOk());
        owner.postIfMatch(RECORD + "/versions/{version}/restore", "\"4\"", org, id, 2)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("transition_not_allowed"));

        viewer.get(RECORD + "/transitions", org, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[0].toState").value("rejected"))
                .andExpect(jsonPath("$.items[0].fromState").value("review"))
                .andExpect(jsonPath("$.items[0].transitionKey").value("reject"))
                .andExpect(jsonPath("$.items[0].comment").value("Duplicate"))
                .andExpect(jsonPath("$.items[0].recordVersion").value(4))
                .andExpect(jsonPath("$.items[0].actorUserId").value(blueAgent.id()))
                .andExpect(jsonPath("$.items[1].transitionKey").value("submit"))
                .andExpect(jsonPath("$.items[1].comment").doesNotExist())
                .andExpect(jsonPath("$.items[2].fromState").doesNotExist())
                .andExpect(jsonPath("$.items[2].toState").value("new"))
                .andExpect(jsonPath("$.items[2].transitionKey").doesNotExist())
                .andExpect(jsonPath("$.items[2].recordVersion").value(1));
        viewer.get(RECORD + "/transitions", org, UUID.randomUUID()).andExpect(status().isNotFound());
        api.newUser().get(RECORD + "/transitions", org, id).andExpect(status().isNotFound());
    }

    @Test
    void availableTransitionsReflectRoleScopeAndState() throws Exception {
        String id = createRecord(owner, org, "ticket", "{\"title\": \"T\", \"team\": \"blue\"}");
        String available = RECORD + "/transitions/available";
        blueAgent.get(available, org, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordId").value(id))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.field").value("stage"))
                .andExpect(jsonPath("$.currentState").value("new"))
                .andExpect(jsonPath("$.transitions[*].key", contains("submit", "reject")))
                .andExpect(jsonPath("$.transitions[0].to").value("review"))
                .andExpect(jsonPath("$.transitions[0].toLabel").value("In review"))
                .andExpect(jsonPath("$.transitions[0].category").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.transitions[0].color").value("blue"))
                .andExpect(jsonPath("$.transitions[0].requireComment").value(false));
        redAgent.get(available, org, id).andExpect(jsonPath("$.transitions", hasSize(0)));
        viewer.get(available, org, id).andExpect(jsonPath("$.transitions", hasSize(0)));

        patch(owner, id, "{\"stage\": \"review\"}").andExpect(status().isOk());
        blueAgent.get(available, org, id).andExpect(jsonPath("$.transitions[*].key", contains("reject")));
        manager.get(available, org, id).andExpect(jsonPath("$.transitions[*].key", contains("approve", "reject")));
        patch(owner, id, "{\"stage\": \"rejected\"}").andExpect(status().isOk());
        owner.get(available, org, id)
                .andExpect(jsonPath("$.transitions[*].key", contains("reopen")))
                .andExpect(jsonPath("$.transitions[0].requireComment").value(true));
    }

    @Test
    void bulkTransitionsReportEveryRecordAndRespectScopes() throws Exception {
        String blue1 = createRecord(owner, org, "ticket", "{\"title\": \"B1\", \"team\": \"blue\"}");
        String blue2 = createRecord(owner, org, "ticket", "{\"title\": \"B2\", \"team\": \"blue\"}");
        String red = createRecord(owner, org, "ticket", "{\"title\": \"R\", \"team\": \"red\"}");
        String blueInReview = createRecord(owner, org, "ticket", "{\"title\": \"B3\", \"team\": \"blue\"}");
        patch(owner, blueInReview, "{\"stage\": \"review\"}").andExpect(status().isOk());
        String missing = UUID.randomUUID().toString();
        String bulk = "/api/v1/orgs/{org}/modules/ticket/transitions/bulk";

        blueAgent.post(bulk, "{\"recordIds\": [\"" + blue1 + "\", \"" + red + "\", \"" + blueInReview + "\", \""
                + missing + "\", \"" + blue2 + "\", \"" + blue1 + "\"], \"to\": \"review\", \"comment\": \"Batch\"}",
                org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(2))
                .andExpect(jsonPath("$.failed").value(3))
                .andExpect(jsonPath("$.results", hasSize(5)))
                .andExpect(jsonPath("$.results[0].recordId").value(blue1))
                .andExpect(jsonPath("$.results[0].ok").value(true))
                .andExpect(jsonPath("$.results[0].version").value(2))
                .andExpect(jsonPath("$.results[0].error").doesNotExist())
                .andExpect(jsonPath("$.results[1].ok").value(false))
                .andExpect(jsonPath("$.results[1].error.code").value("not_found"))
                .andExpect(jsonPath("$.results[2].error.code").value("transition_not_allowed"))
                .andExpect(jsonPath("$.results[3].error.code").value("not_found"))
                .andExpect(jsonPath("$.results[4].ok").value(true));
        owner.get(RECORD, org, red).andExpect(jsonPath("$.data.stage").value("new"));
        owner.get(RECORD + "/transitions", org, blue2)
                .andExpect(jsonPath("$.items[0].comment").value("Batch"))
                .andExpect(jsonPath("$.items[0].transitionKey").value("submit"));

        viewer.post(bulk, "{\"recordIds\": [\"" + blue1 + "\"], \"to\": \"approved\"}", org)
                .andExpect(status().isForbidden());
        manager.post(bulk, "{\"recordIds\": [], \"to\": \"approved\"}", org).andExpect(status().isBadRequest());
        manager.post(bulk, "{\"recordIds\": [\"" + blue1 + "\"], \"to\": \"bogus\"}", org)
                .andExpect(status().isBadRequest());
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= 500; i++) {
            tooMany.add("\"" + UUID.randomUUID() + "\"");
        }
        manager.post(bulk, "{\"recordIds\": [" + String.join(",", tooMany) + "], \"to\": \"approved\"}", org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("recordIds"));
    }

    @Test
    void transitionsOfExistingRecordsPublishEvents() throws Exception {
        String id = createRecord(owner, org, "ticket", "{\"title\": \"T\"}");
        assertThat(events.stream(WorkflowTransitioned.class)).isEmpty();
        patch(manager, id, "{\"stage\": \"review\"}").andExpect(status().isOk());
        transitionAs(manager, id, "\"2\"", "{\"to\": \"approved\", \"comment\": \"Ship it\"}")
                .andExpect(status().isOk());
        List<WorkflowTransitioned> published = events.stream(WorkflowTransitioned.class).toList();
        assertThat(published).hasSize(2);
        WorkflowTransitioned last = published.getLast();
        assertThat(last.organizationId()).isEqualTo(UUID.fromString(org));
        assertThat(last.moduleKey()).isEqualTo("ticket");
        assertThat(last.recordId()).isEqualTo(UUID.fromString(id));
        assertThat(last.fromState()).isEqualTo("review");
        assertThat(last.toState()).isEqualTo("approved");
        assertThat(last.transitionKey()).isEqualTo("approve");
        assertThat(last.comment()).isEqualTo("Ship it");
        assertThat(last.actorUserId()).isEqualTo(UUID.fromString(manager.id()));
        assertThat(published.stream().map(WorkflowTransitioned::toState).collect(Collectors.toList()))
                .containsExactly("review", "approved");
    }

    private ResultActions patch(Caller caller, String id, String data) throws Exception {
        MvcResult current = owner.get(RECORD, org, id).andExpect(status().isOk()).andReturn();
        return caller.patch(RECORD, etag(current), "{\"data\": " + data + "}", org, id);
    }

    private ResultActions transitionAs(Caller caller, String id, String ifMatch, String body) throws Exception {
        return caller.postWithIfMatch(RECORD + "/transitions", ifMatch, body, org, id);
    }

    private static JsonNode json(String value) {
        return JsonMapper.shared().readTree(value);
    }
}
