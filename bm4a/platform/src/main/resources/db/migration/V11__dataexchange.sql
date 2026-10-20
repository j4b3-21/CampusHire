-- Spreadsheet import jobs (ADR-005). Workers claim QUEUED jobs with FOR UPDATE SKIP LOCKED; RUNNING jobs refresh
-- heartbeat_at after every batch so jobs of a crashed worker can be recovered.
CREATE TABLE import_job (
    id              UUID PRIMARY KEY,
    organization_id UUID          NOT NULL REFERENCES organization (id),
    module_id       UUID          NOT NULL REFERENCES module_definition (id),
    module_key      VARCHAR(63)   NOT NULL,
    file_id         UUID          NOT NULL REFERENCES stored_file (id),
    file_name       VARCHAR(255)  NOT NULL,
    format          VARCHAR(10)   NOT NULL CHECK (format IN ('XLSX', 'CSV')),
    mode            VARCHAR(10)   NOT NULL CHECK (mode IN ('CREATE', 'UPSERT')),
    match_field     VARCHAR(63),
    -- {"<column header>": "<field api key>"} for every imported column
    mapping         JSONB         NOT NULL CHECK (jsonb_typeof(mapping) = 'object'),
    dry_run         BOOLEAN       NOT NULL,
    status          VARCHAR(20)   NOT NULL
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    total_rows      INTEGER,
    processed_rows  INTEGER       NOT NULL DEFAULT 0,
    created_rows    INTEGER       NOT NULL DEFAULT 0,
    updated_rows    INTEGER       NOT NULL DEFAULT 0,
    failed_rows     INTEGER       NOT NULL DEFAULT 0,
    error_file_id   UUID REFERENCES stored_file (id),
    -- the first failed rows ([{row, column, field, code, message}]) for display without downloading the error file
    error_sample    JSONB         NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(error_sample) = 'array'),
    error_message   VARCHAR(1000),
    created_by      UUID          NOT NULL REFERENCES platform_user (id),
    created_at      TIMESTAMPTZ   NOT NULL,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    heartbeat_at    TIMESTAMPTZ,
    CONSTRAINT ck_import_job_match_field CHECK ((mode = 'UPSERT') = (match_field IS NOT NULL))
);

-- One queued or running job per module and organization.
CREATE UNIQUE INDEX uq_import_job_active ON import_job (organization_id, module_id)
    WHERE status IN ('QUEUED', 'RUNNING');
-- Worker claim (oldest first) and stale-job recovery.
CREATE INDEX ix_import_job_queue ON import_job (created_at, id) WHERE status = 'QUEUED';
CREATE INDEX ix_import_job_running ON import_job (heartbeat_at) WHERE status = 'RUNNING';
CREATE INDEX ix_import_job_module ON import_job (organization_id, module_id, created_at DESC, id);
