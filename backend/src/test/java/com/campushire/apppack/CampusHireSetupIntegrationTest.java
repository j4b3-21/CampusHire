package com.campushire.apppack;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.bm4a.platform.support.Fixtures;
import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestApi;
import com.bm4a.platform.support.TestApi.Caller;

@IntegrationTest
class CampusHireSetupIntegrationTest {

    static final String SETUP = "/api/v1/orgs/{org}/campushire/setup";

    @Autowired
    MockMvc mvc;

    TestApi api;
    Caller owner;
    String org;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestApi(mvc);
        owner = api.newUser();
        org = Fixtures.createOrg(owner, "IIIT Trichy");
    }

    @Test
    void installsThePackMakesTheCallerPlacementOfficerAndIsIdempotent() throws Exception {
        owner.get(SETUP, org).andExpect(status().isOk())
                .andExpect(jsonPath("$.installed").value(false))
                .andExpect(jsonPath("$.availableVersion").value(1))
                .andExpect(jsonPath("$.upgradeAvailable").value(true));

        owner.post(SETUP, "{\"allowedEmailDomains\": [\"@IIITT.ac.in\", \"iiitt.ac.in\"]}", org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installed").value(true))
                .andExpect(jsonPath("$.report.status").value("INSTALLED"))
                .andExpect(jsonPath("$.report.created.roles",
                        containsInAnyOrder("tpo", "coordinator", "student", "recruiter")))
                .andExpect(jsonPath("$.report.created.workflows", containsInAnyOrder("application")))
                .andExpect(jsonPath("$.roleKey").value("tpo"))
                .andExpect(jsonPath("$.allowedEmailDomains", containsInAnyOrder("iiitt.ac.in")));

        owner.get("/api/v1/orgs/{org}/roles", org)
                .andExpect(jsonPath("$[*].apiKey", containsInAnyOrder("tpo", "coordinator", "student", "recruiter")));
        owner.get("/api/v1/orgs/{org}/me/access", org)
                .andExpect(jsonPath("$.membershipRole").value("OWNER"))
                .andExpect(jsonPath("$.role.key").value("tpo"));
        owner.get("/api/v1/orgs/{org}", org)
                .andExpect(jsonPath("$.settings.campushire.allowedEmailDomains", containsInAnyOrder("iiitt.ac.in")))
                .andExpect(jsonPath("$.settings.campushire.policy.maxOffersPerStudent").value(1));

        owner.post(SETUP, "", org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.status").value("UP_TO_DATE"))
                .andExpect(jsonPath("$.report.created.modules", hasSize(0)))
                .andExpect(jsonPath("$.allowedEmailDomains", containsInAnyOrder("iiitt.ac.in")));
        owner.post(SETUP, "{\"allowedEmailDomains\": [\"iiitt.ac.in\"]}", org)
                .andExpect(status().isOk());
        owner.get(SETUP, org).andExpect(status().isOk())
                .andExpect(jsonPath("$.installed").value(true))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.upgradeAvailable").value(false))
                .andExpect(jsonPath("$.installedAt").isNotEmpty());
        owner.get("/api/v1/orgs/{org}/campushire/me", org).andExpect(status().isOk())
                .andExpect(jsonPath("$.role.key").value("tpo"))
                .andExpect(jsonPath("$.onboarding.status").value("complete"))
                .andExpect(jsonPath("$.capabilities.student.create").value(true));
    }

    @Test
    void refusesToReplaceConfiguredDomainsAndValidatesThem() throws Exception {
        owner.post(SETUP, "{\"allowedEmailDomains\": [\"not a domain\", \"x\"]}", org)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors[*].field",
                        containsInAnyOrder("allowedEmailDomains[0]", "allowedEmailDomains[1]")));
        owner.post(SETUP, "{\"allowedEmailDomains\": [\"iiitt.ac.in\"]}", org).andExpect(status().isOk());

        owner.post(SETUP, "{\"allowedEmailDomains\": [\"other.edu\"]}", org)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("allowed_domains_configured"));
    }

    @Test
    void emptyDomainListCountsAsNotConfigured() throws Exception {
        owner.post(SETUP, "", org).andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedEmailDomains", hasSize(0)));

        owner.post(SETUP, "{\"allowedEmailDomains\": [\"iiitt.ac.in\"]}", org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedEmailDomains", containsInAnyOrder("iiitt.ac.in")));
    }

    @Test
    void onlyAdminsCanSetUp() throws Exception {
        Caller member = api.newUser();
        Fixtures.addMember(owner, org, member, "MEMBER");

        member.post(SETUP, "{}", org).andExpect(status().isForbidden());
        api.newUser().post(SETUP, "{}", org).andExpect(status().isNotFound());
        member.get(SETUP, org).andExpect(status().isOk()).andExpect(jsonPath("$.installed").value(false));
    }
}
