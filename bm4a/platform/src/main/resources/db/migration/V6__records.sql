-- Generic record engine (ADR-001). One row per business record; field values live in `data`, keyed by the
-- field API key, in canonical encoding. System columns are never stored inside `data`.
CREATE TABLE record (
    id                 UUID PRIMARY KEY,
    organization_id    UUID        NOT NULL REFERENCES organization (id),
    module_id          UUID        NOT NULL REFERENCES module_definition (id),
    data               JSONB       NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(data) = 'object'),
    version            BIGINT      NOT NULL DEFAULT 1,
    definition_version BIGINT      NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    created_by         UUID        NOT NULL REFERENCES platform_user (id),
    updated_at         TIMESTAMPTZ NOT NULL,
    updated_by         UUID        NOT NULL REFERENCES platform_user (id),
    deleted_at         TIMESTAMPTZ,
    deleted_by         UUID REFERENCES platform_user (id)
);

-- Default listing (newest first) and trash listing, always within one tenant and module.
CREATE INDEX ix_record_scope_created ON record (organization_id, module_id, created_at DESC, id)
    WHERE deleted_at IS NULL;
CREATE INDEX ix_record_scope_deleted ON record (organization_id, module_id, deleted_at DESC)
    WHERE deleted_at IS NOT NULL;
-- Equality/containment filters (data @> '{"key": value}') and lookup reference checks.
CREATE INDEX ix_record_data ON record USING gin (data jsonb_path_ops);

-- Immutable history: one snapshot per record version.
CREATE TABLE record_version (
    record_id       UUID        NOT NULL REFERENCES record (id),
    version         BIGINT      NOT NULL,
    organization_id UUID        NOT NULL REFERENCES organization (id),
    change_type     VARCHAR(20) NOT NULL CHECK (change_type IN ('CREATED', 'UPDATED', 'DELETED', 'RESTORED', 'REVERTED')),
    data            JSONB       NOT NULL,
    deleted         BOOLEAN     NOT NULL,
    changed_at      TIMESTAMPTZ NOT NULL,
    changed_by      UUID        NOT NULL REFERENCES platform_user (id),
    PRIMARY KEY (record_id, version)
);

-- Atomic per-field uniqueness for fields flagged `unique` (not expressible on JSONB without runtime DDL).
CREATE TABLE record_unique_value (
    organization_id  UUID NOT NULL REFERENCES organization (id),
    field_id         UUID NOT NULL REFERENCES field_definition (id),
    normalized_value TEXT NOT NULL,
    record_id        UUID NOT NULL REFERENCES record (id),
    PRIMARY KEY (organization_id, field_id, normalized_value)
);

CREATE INDEX ix_record_unique_value_record ON record_unique_value (record_id);
