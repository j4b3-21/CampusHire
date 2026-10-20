package com.bm4a.platform.modules.tenancy;

import static com.bm4a.platform.support.Fixtures.addMember;
import static com.bm4a.platform.support.Fixtures.createModule;
import static com.bm4a.platform.support.Fixtures.createOrg;
import static com.bm4a.platform.support.Fixtures.createRole;
import static com.bm4a.platform.support.TestApi.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.tenancy.MembershipAdministration.MembershipView;
import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestApi;
import com.bm4a.platform.support.TestApi.Caller;
import com.bm4a.platform.support.TestJwts;

/**
 * E-mail invitations and trusted membership administration (ADR-004).
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class InvitationIntegrationTest {

    static final String INVITATIONS = "/api/v1/orgs/{org}/invitations";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MembershipAdministration memberships;

    TestApi api;
    Caller owner;
    String org;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc);
        owner = api.newUser();
        org = createOrg(owner, "Invites");
        createModule(owner, org, """
                {"apiKey": "company", "labelSingular": "Company", "labelPlural": "Companies", "settings": {"accessMode": "ROLES"},
                 "fields": [{"apiKey": "name", "label": "Name", "type": "TEXT", "required": true}]}
                """);
        createRole(owner, org, """
                {"apiKey": "recruiter", "label": "Recruiter", "permissions": {"modules": {"company": {
                  "read": {"scope": "ATTRIBUTE", "field": "$id", "attribute": "company"}}}}}
                """);
    }

    @Test
    void invitedUserWithVerifiedMatchingEmailJoinsWithRoleAndAttributes(CapturedOutput output) throws Exception {
        String company = UUID.randomUUID().toString();
        TestJwts.Builder identity = TestJwts.newUser().email("Recruiter." + UUID.randomUUID() + "@Example.test");
        owner.post(INVITATIONS, "{\"email\":\"not-an-email\",\"membershipRole\":\"MEMBER\"}", org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("email"));

        String token = invite(identity, "MEMBER", "recruiter", "{\"company\":\"" + company + "\"}");
        assertThat(token).hasSize(43);
        assertThat(jdbc.sql("SELECT count(*) FROM invitation WHERE token_hash = :token").param("token", token)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM invitation WHERE token_hash = :hash").param("hash", sha256(token))
                .query(Long.class).single()).isOne();
        assertThat(output.getOut()).contains("no InvitationMailer is configured").doesNotContain(token);

        Caller invitee = api.as(identity);
        invitee.get("/api/v1/invitations/{token}", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationName").value("Invites"))
                .andExpect(jsonPath("$.roleLabel").value("Recruiter"))
                .andExpect(jsonPath("$.email").value("r***@example.test"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        invitee.post("/api/v1/invitations/{token}/accept", "", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MEMBER"))
                .andExpect(jsonPath("$.roleKey").value("recruiter"))
                .andExpect(jsonPath("$.attributes.company").value(company));

        invitee.get("/api/v1/orgs/{org}/me/access", org)
                .andExpect(jsonPath("$.role.key").value("recruiter"))
                .andExpect(jsonPath("$.modules.company.readScope").value("ATTRIBUTE"));
        invitee.post("/api/v1/invitations/{token}/accept", "", token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("invitation_used"));
        owner.get(INVITATIONS + "?status=ACCEPTED", org)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].acceptedBy").value(invitee.id()))
                .andExpect(jsonPath("$.items[0].acceptUrl").doesNotExist());
        owner.get("/api/v1/orgs/{org}/audit-events?resourceType=invitation", org)
                .andExpect(jsonPath("$.items[*].action",
                        containsInAnyOrder("invitation.created", "invitation.accepted")));
    }

    @Test
    void acceptRequiresTheVerifiedInvitedEmail() throws Exception {
        TestJwts.Builder invited = TestJwts.newUser();
        String token = invite(invited, "MEMBER", null, null);

        api.newUser().post("/api/v1/invitations/{token}/accept", "", token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("invitation_email_mismatch"));
        api.as(TestJwts.newUser().email(emailOf(invited)).emailVerified(false))
                .post("/api/v1/invitations/{token}/accept", "", token)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("email_not_verified"));
        api.newUser().post("/api/v1/invitations/{token}/accept", "", "A".repeat(43)).andExpect(status().isNotFound());
        api.newUser().get("/api/v1/invitations/{token}", "not-a-token").andExpect(status().isNotFound());
        api.as(invited).post("/api/v1/invitations/{token}/accept", "", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleKey").doesNotExist());
    }

    @Test
    void expiredAndRevokedInvitationsAreGone() throws Exception {
        TestJwts.Builder late = TestJwts.newUser();
        String expired = invite(late, "MEMBER", "recruiter", null);
        jdbc.sql("UPDATE invitation SET expires_at = now() - interval '1 minute' WHERE organization_id = :org")
                .param("org", UUID.fromString(org)).update();
        api.as(late).get("/api/v1/invitations/{token}", expired).andExpect(jsonPath("$.status").value("EXPIRED"));
        api.as(late).post("/api/v1/invitations/{token}/accept", "", expired)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("invitation_expired"));
        // An expired invitation no longer blocks a new one, and no longer holds its role.
        owner.delete("/api/v1/orgs/{org}/roles/recruiter", "\"1\"", org).andExpect(status().isNoContent());

        TestJwts.Builder revokedUser = TestJwts.newUser();
        String revoked = invite(revokedUser, "MEMBER", null, null);
        String id = read(owner.get(INVITATIONS + "?status=PENDING", org).andReturn(), "$.items[0].id");
        owner.post(INVITATIONS, "{\"email\":\"" + emailOf(revokedUser) + "\",\"membershipRole\":\"MEMBER\"}", org)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("invitation_pending"));
        owner.delete(INVITATIONS + "/{id}", null, org, id).andExpect(status().isNoContent());
        owner.delete(INVITATIONS + "/{id}", null, org, id).andExpect(status().isConflict());
        api.as(revokedUser).post("/api/v1/invitations/{token}/accept", "", revoked)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("invitation_revoked"));
        owner.get(INVITATIONS + "?status=EXPIRED", org).andExpect(jsonPath("$.totalElements").value(1));
        owner.get(INVITATIONS + "?status=REVOKED", org).andExpect(jsonPath("$.totalElements").value(1));
        owner.get(INVITATIONS + "?status=bogus", org).andExpect(status().isBadRequest());
    }

    @Test
    void onlyOwnersInviteAdminsAndOutsidersSeeNothing() throws Exception {
        Caller admin = api.newUser();
        addMember(owner, org, admin, "ADMIN");
        admin.post(INVITATIONS, "{\"email\":\"boss@example.test\",\"membershipRole\":\"ADMIN\"}", org)
                .andExpect(status().isForbidden());
        admin.post(INVITATIONS, "{\"email\":\"staff@example.test\",\"membershipRole\":\"MEMBER\","
                + "\"roleKey\":\"nope\"}", org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("unknown_role"));
        admin.post(INVITATIONS, "{\"email\":\"staff@example.test\",\"membershipRole\":\"MEMBER\","
                + "\"attributes\":{\"company\":42}}", org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("attributes.company"));
        admin.post(INVITATIONS, "{\"email\":\"staff@example.test\",\"membershipRole\":\"MEMBER\"}", org)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.acceptUrl", startsWith("http://localhost:3000/invite/")));
        owner.post(INVITATIONS, "{\"email\":\"boss@example.test\",\"membershipRole\":\"ADMIN\"}", org)
                .andExpect(status().isCreated());

        Caller member = api.newUser();
        addMember(owner, org, member, "MEMBER");
        member.get(INVITATIONS, org).andExpect(status().isForbidden());
        api.newUser().get(INVITATIONS, org).andExpect(status().isNotFound());
    }

    @Test
    void trustedMembershipAdministrationIsIdempotentAndAudited() throws Exception {
        UUID orgId = UUID.fromString(org);
        UUID actor = UUID.fromString(owner.id());
        Caller student = api.newUser();
        UUID user = UUID.fromString(student.id());

        MembershipView granted = memberships.grant(orgId, user, MembershipRole.MEMBER, "recruiter",
                Map.of("company", List.of("c-1", "c-2")), actor);
        assertThat(granted.active()).isTrue();
        assertThat(granted.roleKey()).isEqualTo("recruiter");
        assertThat(granted.attributes()).containsEntry("company", List.of("c-1", "c-2"));
        assertThat(memberships.grant(orgId, user, MembershipRole.MEMBER, "recruiter",
                Map.of("company", List.of("c-1", "c-2")), actor).updatedAt()).isEqualTo(granted.updatedAt());

        memberships.updateAttributes(orgId, user, Map.of("company", "c-3"), actor);
        assertThat(memberships.find(orgId, user).orElseThrow().attributes()).isEqualTo(Map.of("company", "c-3"));
        student.get("/api/v1/orgs/{org}/me/access", org).andExpect(jsonPath("$.attributes.company").value("c-3"));

        owner.delete("/api/v1/orgs/{org}/members/{user}", null, org, student.id()).andExpect(status().isNoContent());
        assertThat(memberships.find(orgId, user).orElseThrow().active()).isFalse();
        assertThat(memberships.grant(orgId, user, MembershipRole.MEMBER, null, null, actor).active()).isTrue();
        assertThat(memberships.countRoleAssignments(orgId, UUID.randomUUID())).isZero();

        assertThatThrownBy(() -> memberships.grant(orgId, actor, MembershipRole.MEMBER, null, null, actor))
                .isInstanceOf(ApiException.class).hasMessageContaining("owner");
        assertThatThrownBy(() -> memberships.grant(orgId, user, MembershipRole.MEMBER, null,
                Map.of("Bad Key", "x"), actor)).isInstanceOf(ApiException.class);
        owner.get("/api/v1/orgs/{org}/audit-events?resourceType=membership&resourceId={user}", org, student.id())
                .andExpect(jsonPath("$.items[*].action", containsInAnyOrder("membership.granted",
                        "membership.access_changed", "membership.revoked", "membership.granted")));
    }

    /** Creates an invitation for the identity's e-mail and returns the token from the accept URL. */
    private String invite(TestJwts.Builder identity, String role, String roleKey, String attributes) throws Exception {
        String body = "{\"email\":\"" + emailOf(identity) + "\",\"membershipRole\":\"" + role + "\""
                + (roleKey == null ? "" : ",\"roleKey\":\"" + roleKey + "\"")
                + (attributes == null ? "" : ",\"attributes\":" + attributes) + "}";
        MvcResult result = owner.post(INVITATIONS, body, org)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.email").value(emailOf(identity).toLowerCase()))
                .andReturn();
        String url = read(result, "$.acceptUrl");
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private static String emailOf(TestJwts.Builder identity) {
        return identity.email();
    }

    private static String sha256(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.US_ASCII)));
    }
}
