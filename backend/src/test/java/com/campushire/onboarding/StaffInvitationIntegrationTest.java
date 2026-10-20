package com.campushire.onboarding;

import static com.bm4a.platform.support.TestApi.read;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestApi.Caller;
import com.bm4a.platform.support.TestJwts;
import com.campushire.support.Institute;

@IntegrationTest
class StaffInvitationIntegrationTest {

    static final String RECRUITERS = "/api/v1/orgs/{org}/campushire/companies/{company}/recruiters/invite";
    static final String COORDINATORS = "/api/v1/orgs/{org}/campushire/departments/{department}/coordinators/invite";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    Institute institute;

    @BeforeEach
    void setUp() throws Exception {
        institute = Institute.create(mvc, jdbc);
    }

    @Test
    void invitedRecruiterJoinsBoundToTheirCompany() throws Exception {
        String acme = institute.company("Acme Systems");
        String other = institute.company("Globex");
        String email = "hr." + UUID.randomUUID() + "@acme.example";

        var created = institute.owner.post(RECRUITERS, "{\"email\": \"" + email + "\"}", institute.org, acme)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roleKey").value("recruiter"))
                .andExpect(jsonPath("$.attributes.company").value(acme))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        String acceptUrl = read(created, "$.acceptUrl");
        String token = acceptUrl.substring(acceptUrl.lastIndexOf('/') + 1);

        Caller recruiter = institute.api.as(TestJwts.newUser().email(email));
        recruiter.post("/api/v1/invitations/{token}/accept", "{}", token).andExpect(status().isOk());

        recruiter.get("/api/v1/orgs/{org}/modules/company/records/{id}", institute.org, acme)
                .andExpect(status().isOk());
        recruiter.get("/api/v1/orgs/{org}/modules/company/records/{id}", institute.org, other)
                .andExpect(status().isNotFound());
    }

    @Test
    void coordinatorInvitationCarriesTheDepartment() throws Exception {
        String cse = institute.department("CSE");

        institute.owner.post(COORDINATORS, "{\"email\": \"cse.coord@" + institute.domain + "\"}", institute.org, cse)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roleKey").value("coordinator"))
                .andExpect(jsonPath("$.attributes.department").value(cse));
    }

    @Test
    void onlyPlacementOfficersInviteAndTargetsMustExist() throws Exception {
        String cse = institute.department("CSE");
        Caller coordinator = institute.coordinator(cse).caller();

        coordinator.post(COORDINATORS, "{\"email\": \"x@" + institute.domain + "\"}", institute.org, cse)
                .andExpect(status().isForbidden());
        institute.owner.post(RECRUITERS, "{\"email\": \"hr@acme.example\"}", institute.org, UUID.randomUUID())
                .andExpect(status().isNotFound());
        institute.owner.post(COORDINATORS, "{\"email\": \"not-an-email\"}", institute.org, cse)
                .andExpect(status().isBadRequest());
    }
}
