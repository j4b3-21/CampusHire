package com.campushire.analytics;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.bm4a.platform.modules.packs.PackInstaller;
import com.bm4a.platform.support.Fixtures;
import com.bm4a.platform.support.TestApi.Caller;

import tools.jackson.databind.json.JsonMapper;

/**
 * An organization with the CampusHire app pack installed, plus direct inserts into the projection tables (which the
 * placement module's listeners maintain in production).
 */
final class AnalyticsFixtures {

    private final JdbcClient jdbc;
    private final UUID org;

    private AnalyticsFixtures(JdbcClient jdbc, UUID org) {
        this.jdbc = jdbc;
        this.org = org;
    }

    static AnalyticsFixtures institute(Caller owner, PackInstaller packs, JsonMapper json, JdbcClient jdbc)
            throws Exception {
        UUID org = UUID.fromString(Fixtures.createOrg(owner, "Institute " + UUID.randomUUID()));
        try (InputStream pack = new ClassPathResource("campushire/campushire-pack.json").getInputStream()) {
            packs.install(org, UUID.fromString(owner.id()), json.readTree(pack));
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        return new AnalyticsFixtures(jdbc, org);
    }

    UUID org() {
        return org;
    }

    UUID student(String roll, String name, UUID department, UUID batch, String placementStatus, boolean deleted) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO campushire_student_projection (student_id, organization_id, roll_number, full_name, email,
                    department_id, batch_id, program, placement_status, deleted, record_version, updated_at)
                VALUES (:id, :org, :roll, :name, :email, :department, :batch, 'btech', :status, :deleted, 1, now())
                """)
                .param("id", id).param("org", org).param("roll", roll).param("name", name)
                .param("email", roll.toLowerCase() + "@example.test").param("department", department)
                .param("batch", batch).param("status", placementStatus).param("deleted", deleted)
                .update();
        return id;
    }

    UUID drive(String title, UUID company, String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO campushire_drive_projection (drive_id, organization_id, company_id, title, status,
                    record_version, updated_at)
                VALUES (:id, :org, :company, :title, :status, 1, now())
                """)
                .param("id", id).param("org", org).param("company", company).param("title", title)
                .param("status", status)
                .update();
        return id;
    }

    UUID application(UUID drive, UUID student, UUID company, String stage, String createdAt, boolean deleted) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO campushire_application_projection (application_id, organization_id, drive_id, student_id,
                    company_id, status, deleted, record_version, created_at, updated_at)
                VALUES (:id, :org, :drive, :student, :company, :stage, :deleted, 1, :created, :created)
                """)
                .param("id", id).param("org", org).param("drive", drive).param("student", student)
                .param("company", company).param("stage", stage).param("deleted", deleted)
                .param("created", OffsetDateTime.parse(createdAt))
                .update();
        return id;
    }

    UUID offer(UUID student, UUID company, UUID drive, long ctc, String status, String createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO campushire_offer_projection (offer_id, organization_id, student_id, company_id, drive_id,
                    ctc_amount, offer_type, status, record_version, created_at, updated_at)
                VALUES (:id, :org, :student, :company, :drive, :ctc, 'full_time', :status, 1, :created, :created)
                """)
                .param("id", id).param("org", org).param("student", student).param("company", company)
                .param("drive", drive).param("ctc", BigDecimal.valueOf(ctc)).param("status", status)
                .param("created", OffsetDateTime.parse(createdAt))
                .update();
        return id;
    }
}
