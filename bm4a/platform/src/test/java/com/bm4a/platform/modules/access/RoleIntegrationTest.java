package com.bm4a.platform.modules.access;

import static com.bm4a.platform.support.Fixtures.addMember;
import static com.bm4a.platform.support.Fixtures.addMemberWithRole;
import static com.bm4a.platform.support.Fixtures.createModule;
import static com.bm4a.platform.support.Fixtures.createOrg;
import static com.bm4a.platform.support.Fixtures.createRecord;
import static com.bm4a.platform.support.Fixtures.createRole;
import static com.bm4a.platform.support.TestApi.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.bm4a.platform.modules.authorization.AuthorizationService;
import com.bm4a.platform.modules.authorization.ModulePermission;
import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestApi;
import com.bm4a.platform.support.TestApi.Caller;

/**
 * Org role administration, permission document validation, ROLES/MEMBERS access modes, and the OpenFGA tuples derived
 * from modules, roles and role assignments (ADR-004).
 */
@IntegrationTest
class RoleIntegrationTest {

    static final String ROLES = "/api/v1/orgs/{org}/roles";
    static final String ROLE = ROLES + "/{key}";
    static final String TICKET = """
            {"apiKey": "ticket", "labelSingular": "Ticket", "labelPlural": "Tickets", "settings": {"accessMode": "ROLES"},
             "fields": [
               {"apiKey": "title", "label": "Title", "type": "TEXT", "required": true},
               {"apiKey": "team", "label": "Team", "type": "TEXT", "filterable": true},
               {"apiKey": "body", "label": "Body", "type": "LONG_TEXT"},
               {"apiKey": "tags", "label": "Tags", "type": "MULTI_SELECT", "filterable": true,
                "config": {"options": [{"value": "bug", "label": "Bug"}, {"value": "ops", "label": "Ops"}]}}]}
            """;
    static final String NOTE = """
            {"apiKey": "note", "labelSingular": "Note", "labelPlural": "Notes",
             "fields": [{"apiKey": "text", "label": "Text", "type": "TEXT", "required": true}]}
            """;
    static final String AGENT = """
            {"apiKey": "agent", "label": "Agent", "description": "Support agents",
             "permissions": {"modules": {"ticket": {"read": "ALL", "create": true, "update": "OWN"}}}}
            """;

    final MockMvc mvc;
    final AuthorizationService authorization;

    TestApi api;
    Caller owner;
    String org;

    RoleIntegrationTest(@Autowired MockMvc mvc, @Autowired AuthorizationService authorization) {
        this.mvc = mvc;
        this.authorization = authorization;
    }

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc);
        owner = api.newUser();
        org = createOrg(owner, "Roles");
        createModule(owner, org, TICKET);
        createModule(owner, org, NOTE);
    }

    @Test
    void roleCrudWithCanonicalDocumentsAndConcurrency() throws Exception {
        owner.post(ROLES, AGENT, org)
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().string("Location", "/api/v1/orgs/" + org + "/roles/agent"))
                .andExpect(jsonPath("$.apiKey").value("agent"))
                .andExpect(jsonPath("$.permissions.modules.ticket.read").value("ALL"))
                .andExpect(jsonPath("$.permissions.modules.ticket.create").value(true))
                .andExpect(jsonPath("$.permissions.modules.ticket.update").value("OWN"))
                .andExpect(jsonPath("$.permissions.modules.ticket.delete").value("NONE"))
                .andExpect(jsonPath("$.permissions.modules.ticket.hiddenFields").isEmpty());
        owner.post(ROLES, AGENT, org).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("role_exists"));
        owner.get(ROLES, org).andExpect(jsonPath("$[*].apiKey", containsInAnyOrder("agent")));

        String update = """
                {"label": "Agent (L2)", "permissions": {"modules": {"ticket": {
                  "read": {"scope": "ATTRIBUTE", "field": "team", "attribute": "team", "where": {"tags": "ops"}},
                  "hiddenFields": ["body", "body"]}}}}
                """;
        owner.put(ROLE, null, update, org, "agent").andExpect(status().isPreconditionRequired());
        owner.put(ROLE, "\"1\"", update, org, "agent")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.label").value("Agent (L2)"))
                .andExpect(jsonPath("$.permissions.modules.ticket.read.scope").value("ATTRIBUTE"))
                .andExpect(jsonPath("$.permissions.modules.ticket.read.where.tags[0][0]").value("ops"))
                .andExpect(jsonPath("$.permissions.modules.ticket.hiddenFields.length()").value(1))
                .andExpect(jsonPath("$.permissions.modules.ticket.create").value(false));
        owner.put(ROLE, "\"1\"", update, org, "agent").andExpect(status().isPreconditionFailed());
        owner.put(ROLE, "\"2\"", "{\"apiKey\":\"other\",\"label\":\"x\"}", org, "agent")
                .andExpect(status().isBadRequest());

        owner.delete(ROLE, "\"2\"", org, "agent").andExpect(status().isNoContent());
        owner.get(ROLE, org, "agent").andExpect(status().isNotFound());
        owner.get("/api/v1/orgs/{org}/audit-events?resourceType=role", org)
                .andExpect(jsonPath("$.items[*].action",
                        containsInAnyOrder("access.role.created", "access.role.updated", "access.role.deleted")));
    }

    @Test
    void invalidPermissionDocumentsAreRejectedWithFieldErrors() throws Exception {
        owner.post(ROLES, """
                {"apiKey": "bad", "label": "Bad", "permissions": {"extra": 1, "modules": {
                  "nope": {"read": "ALL"},
                  "ticket": {"read": "SOME", "create": "yes", "update": {"scope": "ATTRIBUTE", "field": "body", "attribute": "team"},
                             "delete": {"scope": "OWN", "field": "team"}, "hiddenFields": ["missing"], "share": true}}}}
                """, org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("permissions.extra",
                        "permissions.modules.nope", "permissions.modules.ticket.read",
                        "permissions.modules.ticket.create", "permissions.modules.ticket.update.field",
                        "permissions.modules.ticket.delete", "permissions.modules.ticket.hiddenFields",
                        "permissions.modules.ticket.share")))
                .andExpect(jsonPath("$.errors[?(@.field=='permissions.modules.nope')].code").value("unknown_module"))
                .andExpect(jsonPath("$.errors[?(@.field=='permissions.modules.ticket.update.field')].code")
                        .value("not_filterable"));
        owner.post(ROLES, """
                {"apiKey": "bad", "label": "Bad", "permissions": {"modules": {"ticket": {
                  "read": {"scope": "ALL", "where": {"tags": ["unknown"], "body": "x"}}}}}}
                """, org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("permissions.modules.ticket.read.where.tags",
                        "permissions.modules.ticket.read.where.body")));
        owner.post(ROLES, "{\"apiKey\":\"Bad Key\",\"label\":\"\"}", org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("apiKey", "label")));
        // $id is a valid ATTRIBUTE field.
        owner.post(ROLES, """
                {"apiKey": "self", "label": "Self", "permissions": {"modules": {"ticket": {
                  "read": {"scope": "ATTRIBUTE", "field": "$id", "attribute": "ticket"}}}}}
                """, org).andExpect(status().isCreated());
        owner.get(ROLES, org).andExpect(jsonPath("$[*].apiKey", containsInAnyOrder("self")));
    }

    @Test
    void onlyAdminsManageRolesAndAssignedRolesCannotBeDeleted() throws Exception {
        createRole(owner, org, AGENT);
        Caller member = api.newUser();
        addMemberWithRole(owner, org, member, "agent", null);
        member.get(ROLES, org).andExpect(status().isForbidden());
        member.post(ROLES, AGENT.replace("\"agent\"", "\"mine\""), org).andExpect(status().isForbidden());

        owner.delete(ROLE, "\"1\"", org, "agent")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("role_in_use"));
        owner.patch("/api/v1/orgs/{org}/members/{user}", null, "{\"roleKey\":null}", org, member.id())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleKey").doesNotExist());
        owner.delete(ROLE, "\"1\"", org, "agent").andExpect(status().isNoContent());

        Caller other = api.newUser();
        owner.post("/api/v1/orgs/{org}/members", "{\"userId\":\"" + other.id() + "\",\"role\":\"MEMBER\","
                + "\"roleKey\":\"agent\"}", org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("unknown_role"));
    }

    @Test
    void rolesModeModulesDenyMembersWithoutAMatchingRole() throws Exception {
        createRole(owner, org, AGENT);
        createRole(owner, org, "{\"apiKey\":\"writer\",\"label\":\"Writer\",\"permissions\":{\"modules\":"
                + "{\"note\":{\"read\":\"OWN\",\"create\":true}}}}");
        Caller plain = api.newUser();
        addMember(owner, org, plain, "MEMBER");
        Caller agent = api.newUser();
        addMemberWithRole(owner, org, agent, "agent", null);
        Caller writer = api.newUser();
        addMemberWithRole(owner, org, writer, "writer", null);
        String ticket = createRecord(owner, org, "ticket", "{\"title\":\"Printer\"}");

        // ROLES mode: no role, or a role without the module -> 403.
        plain.get("/api/v1/orgs/{org}/modules/ticket/records", org)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("access_denied"));
        writer.get("/api/v1/orgs/{org}/modules/ticket/records/{id}", org, ticket).andExpect(status().isForbidden());
        agent.get("/api/v1/orgs/{org}/modules/ticket/records/{id}", org, ticket).andExpect(status().isOk());
        agent.delete("/api/v1/orgs/{org}/modules/ticket/records/{id}", "\"1\"", org, ticket)
                .andExpect(status().isForbidden());

        // MEMBERS mode: members without a rule keep full access; a role that lists the module refines it.
        String byOwner = createRecord(owner, org, "note", "{\"text\":\"Owner's\"}");
        plain.get("/api/v1/orgs/{org}/modules/note/records/{id}", org, byOwner).andExpect(status().isOk());
        agent.get("/api/v1/orgs/{org}/modules/note/records/{id}", org, byOwner).andExpect(status().isOk());
        writer.get("/api/v1/orgs/{org}/modules/note/records/{id}", org, byOwner).andExpect(status().isNotFound());
        String mine = createRecord(writer, org, "note", "{\"text\":\"Mine\"}");
        writer.get("/api/v1/orgs/{org}/modules/note/records", org)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(mine))
                .andExpect(jsonPath("$.items[0].ownerUserId").value(writer.id()));
        writer.patch("/api/v1/orgs/{org}/modules/note/records/{id}", "\"1\"", "{\"data\":{\"text\":\"x\"}}", org, mine)
                .andExpect(status().isForbidden());

        owner.get("/api/v1/orgs/{org}/audit-events?action=security.access_denied", org)
                .andExpect(jsonPath("$.items[*].details.permission", hasItem("MODULE_READ:ticket")));
    }

    @Test
    void accessModeIsValidatedAndSwitchingItRevokesMemberAccess() throws Exception {
        owner.post("/api/v1/orgs/{org}/modules", "{\"apiKey\":\"bad\",\"labelSingular\":\"B\",\"labelPlural\":\"Bs\","
                + "\"settings\":{\"accessMode\":\"EVERYONE\"}}", org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("settings.accessMode"));
        Caller plain = api.newUser();
        addMember(owner, org, plain, "MEMBER");
        UUID noteId = UUID.fromString(read(owner.get("/api/v1/orgs/{org}/modules/note", org).andReturn(), "$.id"));
        assertThat(authorization.isAllowed(UUID.fromString(plain.id()), ModulePermission.WRITE, noteId)).isTrue();

        owner.patch("/api/v1/orgs/{org}/modules/note", "\"1\"", "{\"settings\":{\"accessMode\":\"ROLES\"}}", org)
                .andExpect(status().isOk());
        assertThat(authorization.isAllowed(UUID.fromString(plain.id()), ModulePermission.READ, noteId)).isFalse();
        assertThat(authorization.isAllowed(UUID.fromString(owner.id()), ModulePermission.WRITE, noteId)).isTrue();
        plain.get("/api/v1/orgs/{org}/modules/note/records", org).andExpect(status().isForbidden());
        owner.get("/api/v1/orgs/{org}/audit-events?action=metadata.module.updated", org)
                .andExpect(jsonPath("$.items[0].details.changes.accessMode.to").value("ROLES"));
    }

    @Test
    void roleAssignmentAndPermissionChangesAreSynchronizedToOpenFga() throws Exception {
        createRole(owner, org, AGENT);
        Caller agent = api.newUser();
        addMemberWithRole(owner, org, agent, "agent", null);
        UUID user = UUID.fromString(agent.id());
        UUID ticketId = UUID.fromString(read(owner.get("/api/v1/orgs/{org}/modules/ticket", org).andReturn(), "$.id"));
        assertThat(authorization.isAllowed(user, ModulePermission.READ, ticketId)).isTrue();
        assertThat(authorization.isAllowed(user, ModulePermission.WRITE, ticketId)).isTrue();

        // Read-only role: the writer tuple goes away, the reader tuple stays.
        owner.put(ROLE, "\"1\"", "{\"label\":\"Agent\",\"permissions\":{\"modules\":{\"ticket\":{\"read\":\"ALL\"}}}}",
                org, "agent").andExpect(status().isOk());
        assertThat(authorization.isAllowed(user, ModulePermission.READ, ticketId)).isTrue();
        assertThat(authorization.isAllowed(user, ModulePermission.WRITE, ticketId)).isFalse();
        agent.post("/api/v1/orgs/{org}/modules/ticket/records", "{\"data\":{\"title\":\"x\"}}", org)
                .andExpect(status().isForbidden());

        // Module removed from the role: the user loses access entirely.
        owner.put(ROLE, "\"2\"", "{\"label\":\"Agent\",\"permissions\":{\"modules\":{}}}", org, "agent")
                .andExpect(status().isOk());
        assertThat(authorization.isAllowed(user, ModulePermission.READ, ticketId)).isFalse();
        agent.get("/api/v1/orgs/{org}/modules/ticket/records", org).andExpect(status().isForbidden());

        // Role assignment changes and revocation move the role#assignee tuple.
        owner.put(ROLE, "\"3\"", AGENT, org, "agent").andExpect(status().isOk());
        assertThat(authorization.isAllowed(user, ModulePermission.READ, ticketId)).isTrue();
        owner.patch("/api/v1/orgs/{org}/members/{user}", null, "{\"roleKey\":null}", org, agent.id())
                .andExpect(status().isOk());
        assertThat(authorization.isAllowed(user, ModulePermission.READ, ticketId)).isFalse();
        owner.patch("/api/v1/orgs/{org}/members/{user}", null, "{\"roleKey\":\"agent\"}", org, agent.id())
                .andExpect(jsonPath("$.roleKey").value("agent"));
        assertThat(authorization.isAllowed(user, ModulePermission.READ, ticketId)).isTrue();
        owner.delete("/api/v1/orgs/{org}/members/{user}", null, org, agent.id()).andExpect(status().isNoContent());
        assertThat(authorization.isAllowed(user, ModulePermission.READ, ticketId)).isFalse();
        owner.get("/api/v1/orgs/{org}/audit-events?action=membership.access_changed", org)
                .andExpect(jsonPath("$.items[0].details.roleKey.to").value("agent"));
    }
}
