-- Workflow engine (ADR-007): one state machine per module and organization on a SINGLE_SELECT field.
CREATE TABLE workflow_definition (
    id              UUID PRIMARY KEY,
    organization_id UUID        NOT NULL REFERENCES organization (id),
    module_id       UUID        NOT NULL REFERENCES module_definition (id),
    field_key       VARCHAR(63) NOT NULL CHECK (field_key ~ '^[a-z][a-z0-9_]{0,62}$'),
    -- Validated canonical document: {"initial": ..., "states": [...], "transitions": [...]}
    definition      JSONB       NOT NULL CHECK (jsonb_typeof(definition) = 'object'),
    version         BIGINT      NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL,
    created_by      UUID        NOT NULL REFERENCES platform_user (id),
    updated_at      TIMESTAMPTZ NOT NULL,
    updated_by      UUID        NOT NULL REFERENCES platform_user (id),
    CONSTRAINT uq_workflow_definition_module UNIQUE (organization_id, module_id)
);

-- Append-only history: exactly one row per change of a record's workflow field, written in the record's transaction.
-- The row is written by a record write guard, i.e. before a new record itself is inserted: the record reference is
-- therefore checked at commit.
CREATE TABLE workflow_transition (
    id              UUID PRIMARY KEY,
    organization_id UUID         NOT NULL REFERENCES organization (id),
    module_id       UUID         NOT NULL REFERENCES module_definition (id),
    record_id       UUID         NOT NULL REFERENCES record (id) DEFERRABLE INITIALLY DEFERRED,
    record_version  BIGINT       NOT NULL,
    from_state      VARCHAR(100),
    to_state        VARCHAR(100) NOT NULL,
    transition_key  VARCHAR(63),
    actor_user_id   UUID         NOT NULL REFERENCES platform_user (id),
    comment         TEXT CHECK (length(comment) <= 2000),
    created_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_workflow_transition_record ON workflow_transition (organization_id, record_id, created_at);
