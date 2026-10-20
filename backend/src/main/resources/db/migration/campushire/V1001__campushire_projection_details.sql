-- Facts needed by the applicant list, the dream-company policy and the dashboards that V1000 did not project.

ALTER TABLE campushire_student_projection
    ADD COLUMN total_backlogs INT          NOT NULL DEFAULT 0,
    ADD COLUMN skills         TEXT,
    ADD COLUMN resume_file_id VARCHAR(200),
    ADD COLUMN resume_name    VARCHAR(255);

-- Shortlist uploads resolve roll numbers case-insensitively.
CREATE INDEX ix_chs_student_org_roll ON campushire_student_projection (organization_id, lower(roll_number))
    WHERE NOT deleted;

-- CTC of the drive's job, kept current from drive and job record events.
ALTER TABLE campushire_drive_projection ADD COLUMN job_ctc NUMERIC(14, 2);
CREATE INDEX ix_chs_drive_org_job ON campushire_drive_projection (organization_id, job_id);
CREATE INDEX ix_chs_drive_org_date ON campushire_drive_projection (organization_id, drive_date) WHERE NOT deleted;

ALTER TABLE campushire_offer_projection ADD COLUMN respond_by DATE;
CREATE INDEX ix_chs_offer_department ON campushire_offer_projection (organization_id, department_id)
    WHERE NOT deleted;

CREATE INDEX ix_chs_application_department ON campushire_application_projection (organization_id, department_id,
                                                                                  created_at) WHERE NOT deleted;

CREATE INDEX ix_chs_registration_application ON campushire_drive_registration (application_id);
