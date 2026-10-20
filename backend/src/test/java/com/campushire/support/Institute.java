package com.campushire.support;

import static com.bm4a.platform.support.TestApi.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.awaitility.Awaitility;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.bm4a.platform.support.Fixtures;
import com.bm4a.platform.support.TestApi;
import com.bm4a.platform.support.TestApi.Caller;
import com.bm4a.platform.support.TestJwts;

/**
 * A CampusHire institute set up through the real APIs: an owner who ran {@code /campushire/setup} with a unique
 * e-mail domain, plus helpers that create app pack records and members exactly like the web app would.
 */
public final class Institute {

    public static final Duration PROJECTION_WAIT = Duration.ofSeconds(20);

    public final TestApi api;
    public final JdbcClient jdbc;
    public final TestJwts.Builder ownerIdentity;
    public final Caller owner;
    public final String org;
    public final String domain;

    private Institute(TestApi api, JdbcClient jdbc, TestJwts.Builder ownerIdentity, Caller owner, String org,
            String domain) {
        this.api = api;
        this.jdbc = jdbc;
        this.ownerIdentity = ownerIdentity;
        this.owner = owner;
        this.org = org;
        this.domain = domain;
    }

    public static Institute create(MockMvc mvc, JdbcClient jdbc) throws Exception {
        TestApi api = new TestApi(mvc);
        TestJwts.Builder identity = TestJwts.newUser();
        Caller owner = api.as(identity);
        String org = Fixtures.createOrg(owner, "Institute " + UUID.randomUUID());
        String domain = "inst" + Long.toHexString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE) + ".ac.in";
        owner.post("/api/v1/orgs/{org}/campushire/setup", "{\"allowedEmailDomains\": [\"" + domain + "\"]}", org)
                .andExpect(status().isOk());
        return new Institute(api, jdbc, identity, owner, org, domain);
    }

    /** Uploads a small PDF through the files API and returns its file ID. */
    public String pdf(String name) throws Exception {
        return Fixtures.uploadFile(owner, org, name, "application/pdf",
                "%PDF-1.4\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII));
    }

    public String record(String module, String data) throws Exception {
        return Fixtures.createRecord(owner, org, module, data);
    }

    public String department(String code) throws Exception {
        return record("department", "{\"name\": \"Department " + code + "\", \"code\": \"" + code + "\"}");
    }

    public String batch(String name) throws Exception {
        return record("batch", "{\"name\": \"" + name + "\", \"program\": \"btech\", \"graduation_year\": 2026}");
    }

    public String email(String local) {
        return local.toLowerCase() + "@" + domain;
    }

    /** A student with the given academics; {@code extra} is appended to the data object (e.g. {@code , "x": 1}). */
    public String student(String roll, String department, String batch, String cgpa, int backlogs, String extra)
            throws Exception {
        return record("student", """
                {"roll_number": "%s", "full_name": "Student %s", "email": "%s", "department": "%s", "batch": "%s",
                 "program": "btech", "cgpa": "%s", "active_backlogs": %d, "tenth_percentage": "85",
                 "twelfth_percentage": "80", "phone": "+91 98765 43210", "date_of_birth": "2004-05-06"%s}
                """.formatted(roll, roll, email(roll), department, batch, cgpa, backlogs, extra == null ? "" : extra));
    }

    public String company(String name) throws Exception {
        return record("company", "{\"name\": \"" + name + " " + UUID.randomUUID() + "\", \"tier\": \"regular\"}");
    }

    public String job(String company, String title, long ctc) throws Exception {
        return record("job", """
                {"title": "%s", "company": "%s", "job_type": "full_time", "ctc": {"amount": "%d", "currency": "INR"}}
                """.formatted(title, company, ctc));
    }

    /** A draft drive whose registration is open from an hour ago until {@code closesIn} from now. */
    public String drive(String company, String job, String eligibility, Duration closesIn) throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return record("drive", """
                {"title": "Drive %s", "company": "%s", "job": "%s", "registration_opens_at": "%s",
                 "registration_closes_at": "%s", "drive_date": "2030-01-15", "mode": "on_campus"%s}
                """.formatted(UUID.randomUUID().toString().substring(0, 8), company, job, now.minusSeconds(3600),
                now.plus(closesIn), eligibility == null ? "" : ", \"eligibility\": " + eligibility));
    }

    public String publishedDrive(String company, String job, String eligibility) throws Exception {
        String drive = drive(company, job, eligibility, Duration.ofDays(2));
        owner.post("/api/v1/orgs/{org}/campushire/drives/{id}/publish", "{}", org, drive)
                .andExpect(status().isOk());
        return drive;
    }

    /** A signed-in student account claimed onto the student record with e-mail {@code email(roll)}. */
    public Account claim(String roll) throws Exception {
        TestJwts.Builder identity = TestJwts.newUser().email(email(roll));
        Caller caller = api.as(identity);
        caller.post("/api/v1/campushire/onboarding/claim", "{\"organizationId\": \"" + org + "\"}")
                .andExpect(status().isOk());
        return new Account(identity, caller);
    }

    public Account member(String roleKey, String attributes) throws Exception {
        TestJwts.Builder identity = TestJwts.newUser();
        Caller caller = api.as(identity);
        Fixtures.addMemberWithRole(owner, org, caller, roleKey, attributes);
        return new Account(identity, caller);
    }

    public Account recruiter(String company) throws Exception {
        return member("recruiter", "{\"company\": \"" + company + "\"}");
    }

    public Account coordinator(String department) throws Exception {
        return member("coordinator", "{\"department\": \"" + department + "\"}");
    }

    /** Moves an application to {@code stage} as the owner through the platform's workflow API. */
    public void move(String application, String stage) throws Exception {
        owner.post("/api/v1/orgs/{org}/modules/application/transitions/bulk",
                "{\"recordIds\": [\"" + application + "\"], \"to\": \"" + stage + "\"}", org)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(1));
    }

    /** Waits until the projection row of {@code id} exists in {@code table} with at least {@code version}. */
    public void awaitProjected(String table, String idColumn, String id, long version) {
        Awaitility.await().atMost(PROJECTION_WAIT).untilAsserted(() -> assertThat(jdbc.sql("SELECT record_version FROM "
                + table + " WHERE " + idColumn + " = :id").param("id", UUID.fromString(id)).query(Long.class)
                .optional()).hasValueSatisfying(projected -> assertThat(projected).isGreaterThanOrEqualTo(version)));
    }

    public void awaitStudent(String id) {
        awaitProjected("campushire_student_projection", "student_id", id, 1);
    }

    public void awaitApplication(String id) {
        awaitProjected("campushire_application_projection", "application_id", id, 1);
    }

    public static String id(ResultActions result) throws Exception {
        return read(result.andReturn(), "$.id");
    }

    public record Account(TestJwts.Builder identity, Caller caller) {
    }
}
