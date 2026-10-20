-- Uploaded files (ADR-005). The content lives in the object store under `storage_key`
-- (`<orgId>/<yyyy>/<mm>/<uuid>`, never derived from client input); this row is the only way to reach it.
CREATE TABLE stored_file (
    id              UUID PRIMARY KEY,
    organization_id UUID         NOT NULL REFERENCES organization (id),
    storage_key     VARCHAR(200) NOT NULL,
    original_name   VARCHAR(255) NOT NULL,
    content_type    VARCHAR(100) NOT NULL,
    size_bytes      BIGINT       NOT NULL CHECK (size_bytes > 0),
    sha256          CHAR(64)     NOT NULL,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE', 'DELETED')),
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      UUID         NOT NULL REFERENCES platform_user (id),
    CONSTRAINT uq_stored_file_storage_key UNIQUE (storage_key)
);

CREATE INDEX ix_stored_file_org_created ON stored_file (organization_id, created_at DESC, id);
