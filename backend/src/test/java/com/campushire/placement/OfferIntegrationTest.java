package com.campushire.placement;

import static com.bm4a.platform.support.TestApi.read;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.bm4a.platform.support.IntegrationTest;
import com.bm4a.platform.support.TestApi.Caller;
import com.campushire.support.Institute;

@IntegrationTest
class OfferIntegrationTest {

    static final String OFFERS = "/api/v1/orgs/{org}/campushire/offers";
    static final String STUDENT = "/api/v1/orgs/{org}/modules/student/records/{id}";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    Institute institute;
    String cse;
    String batch;
    String company;
    String student;
    Caller studentCaller;

    @BeforeEach
    void setUp() throws Exception {
        institute = Institute.create(mvc, jdbc);
        cse = institute.department("CSE");
        batch = institute.batch("2026");
        company = institute.company("Umbrella");
        student = institute.student("OF001", cse, batch, "8.4", 0, null);
        studentCaller = institute.claim("OF001").caller();
    }

    /** An application of the student to a new drive, moved to {@code stage} by the placement officer. */
    private String application(String stage) throws Exception {
        String job = institute.job(company, "Role", 600_000);
        String drive = institute.drive(company, job, null, java.time.Duration.ofDays(1));
        String application = institute.record("application", """
                {"drive": "%s", "student": "%s", "company": "%s", "department": "%s"}
                """.formatted(drive, student, company, cse));
        if (!"applied".equals(stage)) {
            institute.move(application, "shortlisted");
        }
        if ("interview".equals(stage)) {
            institute.move(application, "interview");
        }
        return application;
    }

    private String offer(String application, long ctc, String extra) {
        return """
                {"applicationId": "%s", "ctc": %d, "offerType": "full_time", "joiningDate": "2027-07-01",
                 "respondBy": "%s"%s}
                """.formatted(application, ctc, LocalDate.now().plusDays(7), extra);
    }

    @Test
    void recordsAnOfferMovesTheApplicationAndPlacesTheStudent() throws Exception {
        String application = application("interview");

        String letter = institute.pdf("offer.pdf");
        String offer = read(institute.owner.post(OFFERS, offer(application, 850_000, ", \"offerLetterFileId\": \""
                + letter + "\""), institute.org)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.studentId").value(student))
                .andExpect(jsonPath("$.companyId").value(company))
                .andExpect(jsonPath("$.departmentId").value(cse))
                .andExpect(jsonPath("$.ctc").value(850000))
                .andExpect(jsonPath("$.offerStatus").value("extended"))
                .andExpect(jsonPath("$.offerLetterFileId").value(letter))
                .andExpect(jsonPath("$.applicationStage").value("offered"))
                .andExpect(jsonPath("$.placementStatus").value("placed"))
                .andReturn(), "$.id");

        institute.owner.get(STUDENT, institute.org, student).andExpect(jsonPath("$.data.placement_status")
                .value("placed"));
        institute.owner.post(OFFERS, offer(application, 900_000, ""), institute.org)
                .andExpect(status().isConflict());
        institute.owner.post(OFFERS, offer(application("applied"), 900_000, ""), institute.org)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("offer_policy_violation"));
        institute.recruiter(company).caller().post(OFFERS, offer(application, 900_000, ""), institute.org)
                .andExpect(status().isForbidden());

        studentCaller.post(OFFERS + "/{id}/accept", "{}", institute.org, offer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offerStatus").value("accepted"))
                .andExpect(jsonPath("$.placementStatus").value("placed"));
        studentCaller.post(OFFERS + "/{id}/decline", "{}", institute.org, offer)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("offer_not_pending"));
    }

    @Test
    void enforcesTheOfferPolicyWithDreamUpgradesAndAdminOverrides() throws Exception {
        institute.owner.post(OFFERS, offer(application("shortlisted"), 700_000, ""), institute.org)
                .andExpect(status().isCreated());

        String regular = application("shortlisted");
        institute.owner.post(OFFERS, offer(regular, 800_000, ""), institute.org)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("offer_policy_violation"));
        institute.owner.post(OFFERS, offer(application("shortlisted"), 1_500_000, ""), institute.org)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.policyOverridden").value(false));
        institute.owner.post(OFFERS, offer(regular, 800_000, ", \"override\": true"), institute.org)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.policyOverridden").value(true));
        institute.owner.get("/api/v1/orgs/{org}/audit-events?action=campushire.offer.policy_overridden",
                institute.org).andExpect(jsonPath("$.items[0].actorUserId").value(institute.owner.id()));
    }

    @Test
    void studentsDeclineBeforeTheDeadlineAndDecliningTheLastOfferUnplacesThem() throws Exception {
        String offer = read(institute.owner.post(OFFERS, offer(application("shortlisted"), 600_000, ""),
                institute.org).andExpect(status().isCreated()).andReturn(), "$.id");
        Caller stranger = institute.member("student", "{\"student\": \"" + java.util.UUID.randomUUID() + "\"}")
                .caller();
        stranger.post(OFFERS + "/{id}/decline", "{}", institute.org, offer).andExpect(status().isNotFound());

        studentCaller.post(OFFERS + "/{id}/decline", "{}", institute.org, offer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offerStatus").value("declined"))
                .andExpect(jsonPath("$.placementStatus").value("unplaced"));

        String expired = read(institute.owner.post(OFFERS, """
                {"applicationId": "%s", "ctc": 650000, "respondBy": "%s"}
                """.formatted(application("shortlisted"), LocalDate.now().minusDays(2)), institute.org)
                .andExpect(status().isCreated()).andReturn(), "$.id");
        studentCaller.post(OFFERS + "/{id}/accept", "{}", institute.org, expired)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("offer_expired"));
    }
}
