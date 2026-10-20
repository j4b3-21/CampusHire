package com.bm4a.platform.modules.tenancy;

import static com.bm4a.platform.support.Fixtures.addMember;
import static com.bm4a.platform.support.Fixtures.createModule;
import static com.bm4a.platform.support.Fixtures.createOrg;
import static com.bm4a.platform.support.Fixtures.createRole;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.bm4a.platform.modules.shared.error.ApiException;
import com.bm4a.platform.modules.tenancy.InvitationAdministration.IssuedInvitation;
import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestApi;
import com.bm4a.platform.support.TestApi.Caller;

import tools.jackson.databind.json.JsonMapper;

/**
 * {@link InvitationAdministration} and {@link OrganizationSettings#put} for trusted application code.
 */
@IntegrationTest
class TrustedTenancyApiIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvc mvc;
    private final InvitationAdministration invitations;
    private final OrganizationSettings settings;

    private Caller owner;
    private UUID org;
    private TenantContext tenant;

    @Autowired
    TrustedTenancyApiIntegrationTest(MockMvc mvc, InvitationAdministration invitations,
            OrganizationSettings settings) {
        this.mvc = mvc;
        this.invitations = invitations;
        this.settings = settings;
    }

    @BeforeEach
    void setUp() throws Exception {
        TestApi api = new TestApi(mvc);
        owner = api.newUser();
        org = UUID.fromString(createOrg(owner, "Trusted"));
        createModule(owner, org.toString(), """
                {"apiKey": "company", "labelSingular": "Company", "labelPlural": "Companies", "settings": {"accessMode": "ROLES"},
                 "fields": [{"apiKey": "name", "label": "Name", "type": "TEXT", "required": true}]}
                """);
        createRole(owner, org.toString(), """
                {"apiKey": "recruiter", "label": "Recruiter", "permissions": {"modules": {"company": {
                  "read": {"scope": "ATTRIBUTE", "field": "$id", "attribute": "company"}}}}}
                """);
        tenant = new TenantContext(org, UUID.fromString(owner.id()), MembershipRole.OWNER);
    }

    @Test
    void issuesInvitationsWithRoleAttributesAndOneTimeLink() {
        String company = UUID.randomUUID().toString();

        IssuedInvitation issued = invitations.invite(tenant, "  HR@Acme.Example ", MembershipRole.MEMBER, "recruiter",
                JSON.readTree("{\"company\": \"" + company + "\"}"));

        assertThat(issued.email()).isEqualTo("hr@acme.example");
        assertThat(issued.roleKey()).isEqualTo("recruiter");
        assertThat(issued.attributes()).isEqualTo(Map.of("company", company));
        assertThat(issued.status()).isEqualTo("PENDING");
        assertThat(issued.createdBy()).isEqualTo(tenant.userId());
        assertThat(issued.acceptUrl()).matches("^http://localhost:3000/invite/[A-Za-z0-9_-]{43}$");
        assertThatThrownBy(() -> invitations.invite(tenant, "hr@acme.example", MembershipRole.MEMBER, "recruiter",
                null)).isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("invitation_pending"));
    }

    @Test
    void rejectsUnknownRolesAndAdminInvitesByNonOwners() throws Exception {
        assertThatThrownBy(() -> invitations.invite(tenant, "x@acme.example", MembershipRole.MEMBER, "ghost", null))
                .isInstanceOf(ApiException.class);
        Caller adminUser = new TestApi(mvc).newUser();
        addMember(owner, org.toString(), adminUser, "ADMIN");
        TenantContext admin = new TenantContext(org, UUID.fromString(adminUser.id()), MembershipRole.ADMIN);
        assertThatThrownBy(() -> invitations.invite(admin, "y@acme.example", MembershipRole.ADMIN, null, null))
                .isInstanceOf(ApiException.class);
        assertThat(invitations.invite(admin, "z@acme.example", MembershipRole.MEMBER, null, null).createdBy())
                .isEqualTo(admin.userId());
    }

    @Test
    void putReplacesNestedValuesCreatesParentsAndRemovesOnNull() {
        settings.mergeDefaults(org, tenant.userId(), JSON.readTree("""
                {"campushire": {"allowedEmailDomains": ["old.example"], "policy": {"maxOffersPerStudent": 1}}}
                """));

        assertThat(settings.put(org, tenant.userId(), List.of("campushire", "allowedEmailDomains"),
                JSON.readTree("[\"iiitt.ac.in\"]"))).isTrue();
        assertThat(settings.put(org, tenant.userId(), List.of("campushire", "allowedEmailDomains"),
                JSON.readTree("[\"iiitt.ac.in\"]"))).isFalse();
        assertThat(settings.put(org, tenant.userId(), List.of("branding", "color"), JSON.readTree("\"#5b5bd6\"")))
                .isTrue();
        assertThat(settings.put(org, tenant.userId(), List.of("branding", "color"), null)).isTrue();

        var current = settings.get(org);
        assertThat(current.at("/campushire/allowedEmailDomains/0").stringValue()).isEqualTo("iiitt.ac.in");
        assertThat(current.at("/campushire/policy/maxOffersPerStudent").intValue()).isEqualTo(1);
        assertThat(current.at("/branding").isObject()).isTrue();
        assertThat(current.at("/branding/color").isMissingNode()).isTrue();
    }

    @Test
    void putRejectsEmptyPathsAndNonObjectParents() {
        settings.mergeDefaults(org, tenant.userId(), JSON.readTree("{\"flag\": true}"));

        assertThatThrownBy(() -> settings.put(org, tenant.userId(), List.of(), JSON.readTree("1")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> settings.put(org, tenant.userId(), List.of("flag", "nested"), JSON.readTree("1")))
                .isInstanceOf(ApiException.class);
    }
}
