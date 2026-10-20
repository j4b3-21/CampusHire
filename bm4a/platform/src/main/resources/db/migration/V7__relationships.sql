-- Relationship engine. Many-to-one references are lookup fields stored in record data; this table set adds
-- explicit, cardinality-checked links between records of two modules of the same tenant.
CREATE TABLE relationship_definition (
    id               UUID PRIMARY KEY,
    organization_id  UUID         NOT NULL REFERENCES organization (id),
    api_key          VARCHAR(63)  NOT NULL CHECK (api_key ~ '^[a-z][a-z0-9_]{0,62}$'),
    label            VARCHAR(200) NOT NULL,
    inverse_label    VARCHAR(200),
    source_module_id UUID         NOT NULL REFERENCES module_definition (id),
    target_module_id UUID         NOT NULL REFERENCES module_definition (id),
    cardinality      VARCHAR(20)  NOT NULL CHECK (cardinality IN ('ONE_TO_ONE', 'ONE_TO_MANY', 'MANY_TO_MANY')),
    on_delete        VARCHAR(20)  NOT NULL CHECK (on_delete IN ('RESTRICT', 'DETACH')),
    status           VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    version          BIGINT       NOT NULL DEFAULT 1,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       UUID         NOT NULL REFERENCES platform_user (id),
    updated_at       TIMESTAMPTZ  NOT NULL,
    updated_by       UUID         NOT NULL REFERENCES platform_user (id),
    CONSTRAINT uq_relationship_api_key UNIQUE (organization_id, api_key)
);

CREATE TABLE record_link (
    id               UUID PRIMARY KEY,
    organization_id  UUID        NOT NULL REFERENCES organization (id),
    relationship_id  UUID        NOT NULL REFERENCES relationship_definition (id),
    -- Copied from the (immutable) relationship so the partial unique indexes below can enforce it.
    cardinality      VARCHAR(20) NOT NULL,
    source_record_id UUID        NOT NULL REFERENCES record (id),
    target_record_id UUID        NOT NULL REFERENCES record (id),
    created_at       TIMESTAMPTZ NOT NULL,
    created_by       UUID        NOT NULL REFERENCES platform_user (id),
    CONSTRAINT uq_record_link UNIQUE (relationship_id, source_record_id, target_record_id)
);

-- ONE_TO_MANY: a target belongs to at most one source. ONE_TO_ONE: additionally a source has at most one target.
CREATE UNIQUE INDEX uq_record_link_single_source ON record_link (relationship_id, target_record_id)
    WHERE cardinality IN ('ONE_TO_ONE', 'ONE_TO_MANY');
CREATE UNIQUE INDEX uq_record_link_single_target ON record_link (relationship_id, source_record_id)
    WHERE cardinality = 'ONE_TO_ONE';

CREATE INDEX ix_record_link_source ON record_link (organization_id, source_record_id);
CREATE INDEX ix_record_link_target ON record_link (organization_id, target_record_id);
