-- CampusHire typed projections (ADR-003). Records stay the source of truth; these tables are rebuilt from them and
-- updated by record event listeners. record_version guards against applying an older event after a newer one.

CREATE TABLE campushire_student_projection (
    student_id         UUID PRIMARY KEY,
    organization_id    UUID          NOT NULL REFERENCES organization (id),
    owner_user_id      UUID,
    roll_number        VARCHAR(64)   NOT NULL,
    full_name          VARCHAR(255)  NOT NULL,
    email              VARCHAR(254),
    department_id      UUID,
    batch_id           UUID,
    program            VARCHAR(32),
    cgpa               NUMERIC(4, 2),
    active_backlogs    INT           NOT NULL DEFAULT 0,
    tenth_percentage   NUMERIC(5, 2),
    twelfth_percentage NUMERIC(5, 2),
    placement_status   VARCHAR(32)   NOT NULL DEFAULT 'unplaced',
    profile_verified   BOOLEAN       NOT NULL DEFAULT false,
    deleted            BOOLEAN       NOT NULL DEFAULT false,
    record_version     BIGINT        NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL
);

CREATE INDEX ix_chs_student_org_dept_batch ON campushire_student_projection (organization_id, department_id, batch_id)
    WHERE NOT deleted;
CREATE INDEX ix_chs_student_org_cgpa ON campushire_student_projection (organization_id, cgpa) WHERE NOT deleted;
CREATE INDEX ix_chs_student_org_email ON campushire_student_projection (organization_id, lower(email));
CREATE INDEX ix_chs_student_owner ON campushire_student_projection (organization_id, owner_user_id);

CREATE TABLE campushire_drive_projection (
    drive_id               UUID PRIMARY KEY,
    organization_id        UUID          NOT NULL REFERENCES organization (id),
    company_id             UUID,
    job_id                 UUID,
    title                  VARCHAR(255)  NOT NULL,
    status                 VARCHAR(32)   NOT NULL,
    registration_opens_at  TIMESTAMPTZ,
    registration_closes_at TIMESTAMPTZ,
    drive_date             DATE,
    eligibility            JSONB         NOT NULL DEFAULT '{}'::jsonb,
    deleted                BOOLEAN       NOT NULL DEFAULT false,
    record_version         BIGINT        NOT NULL,
    updated_at             TIMESTAMPTZ   NOT NULL
);

CREATE INDEX ix_chs_drive_org_status ON campushire_drive_projection (organization_id, status, registration_closes_at)
    WHERE NOT deleted;
CREATE INDEX ix_chs_drive_org_company ON campushire_drive_projection (organization_id, company_id);

CREATE TABLE campushire_application_projection (
    application_id  UUID PRIMARY KEY,
    organization_id UUID         NOT NULL REFERENCES organization (id),
    drive_id        UUID         NOT NULL,
    student_id      UUID         NOT NULL,
    company_id      UUID,
    department_id   UUID,
    status          VARCHAR(32)  NOT NULL,
    deleted         BOOLEAN      NOT NULL DEFAULT false,
    record_version  BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_chs_application_drive_status ON campushire_application_projection (organization_id, drive_id, status)
    WHERE NOT deleted;
CREATE INDEX ix_chs_application_student ON campushire_application_projection (organization_id, student_id);
CREATE INDEX ix_chs_application_company ON campushire_application_projection (organization_id, company_id, status);
CREATE INDEX ix_chs_application_created ON campushire_application_projection (organization_id, created_at);

CREATE TABLE campushire_offer_projection (
    offer_id        UUID PRIMARY KEY,
    organization_id UUID          NOT NULL REFERENCES organization (id),
    application_id  UUID,
    student_id      UUID          NOT NULL,
    company_id      UUID,
    drive_id        UUID,
    department_id   UUID,
    ctc_amount      NUMERIC(14, 2),
    offer_type      VARCHAR(32),
    status          VARCHAR(32)   NOT NULL,
    deleted         BOOLEAN       NOT NULL DEFAULT false,
    record_version  BIGINT        NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL
);

CREATE INDEX ix_chs_offer_student ON campushire_offer_projection (organization_id, student_id)
    WHERE NOT deleted AND status IN ('extended', 'accepted');
CREATE INDEX ix_chs_offer_company ON campushire_offer_projection (organization_id, company_id);
CREATE INDEX ix_chs_offer_created ON campushire_offer_projection (organization_id, created_at);

-- One registration per student and drive: makes applying idempotent under concurrent retries (ADR-006).
CREATE TABLE campushire_drive_registration (
    organization_id UUID        NOT NULL REFERENCES organization (id),
    drive_id        UUID        NOT NULL,
    student_id      UUID        NOT NULL,
    application_id  UUID,
    registered_at   TIMESTAMPTZ NOT NULL,
    registered_by   UUID        NOT NULL REFERENCES platform_user (id),
    PRIMARY KEY (drive_id, student_id)
);

CREATE INDEX ix_chs_registration_student ON campushire_drive_registration (organization_id, student_id);

-- Placement statistics per department and batch, refreshed concurrently on a schedule.
CREATE MATERIALIZED VIEW campushire_placement_stats AS
WITH students AS (
    SELECT organization_id, department_id, batch_id,
           count(*)                                                                  AS total_students,
           count(*) FILTER (WHERE placement_status NOT IN ('opted_out', 'higher_studies')) AS participating_students
    FROM campushire_student_projection
    WHERE NOT deleted
    GROUP BY organization_id, department_id, batch_id
), offers AS (
    SELECT s.organization_id, s.department_id, s.batch_id,
           count(DISTINCT o.student_id)                                  AS placed_students,
           count(*)                                                      AS offers,
           max(o.ctc_amount)                                             AS highest_ctc,
           avg(o.ctc_amount)                                             AS average_ctc,
           percentile_cont(0.5) WITHIN GROUP (ORDER BY o.ctc_amount)     AS median_ctc
    FROM campushire_offer_projection o
    JOIN campushire_student_projection s ON s.student_id = o.student_id AND NOT s.deleted
    WHERE NOT o.deleted AND o.status IN ('extended', 'accepted')
    GROUP BY s.organization_id, s.department_id, s.batch_id
)
SELECT st.organization_id, st.department_id, st.batch_id, st.total_students, st.participating_students,
       coalesce(of.placed_students, 0) AS placed_students, coalesce(of.offers, 0) AS offers,
       of.highest_ctc, of.average_ctc, of.median_ctc, now() AS refreshed_at
FROM students st
LEFT JOIN offers of
       ON of.organization_id = st.organization_id
      AND of.department_id IS NOT DISTINCT FROM st.department_id
      AND of.batch_id IS NOT DISTINCT FROM st.batch_id;

CREATE UNIQUE INDEX uq_chs_placement_stats ON campushire_placement_stats (organization_id, department_id, batch_id)
    NULLS NOT DISTINCT;
