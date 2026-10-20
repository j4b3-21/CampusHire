-- Metadata engine: module and field definitions (see docs/architecture/ADR-001-dynamic-record-storage.md).
-- organization_id NULL marks an explicitly platform-global module (managed by platform administrators only).
CREATE TABLE module_definition (
    id              UUID PRIMARY KEY,
    organization_id UUID REFERENCES organization (id),
    api_key         VARCHAR(63)  NOT NULL CHECK (api_key ~ '^[a-z][a-z0-9_]{0,62}$'),
    label_singular  VARCHAR(200) NOT NULL,
    label_plural    VARCHAR(200) NOT NULL,
    description     TEXT,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    settings        JSONB        NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(settings) = 'object'),
    layout          JSONB        NOT NULL DEFAULT '{"sections": []}'::jsonb CHECK (jsonb_typeof(layout) = 'object'),
    -- Definition version: incremented by every change to the module, its fields or its layout.
    version         BIGINT       NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      UUID         NOT NULL REFERENCES platform_user (id),
    updated_at      TIMESTAMPTZ  NOT NULL,
    updated_by      UUID         NOT NULL REFERENCES platform_user (id)
);

CREATE UNIQUE INDEX uq_module_tenant_api_key ON module_definition (organization_id, api_key)
    WHERE organization_id IS NOT NULL;
CREATE UNIQUE INDEX uq_module_global_api_key ON module_definition (api_key)
    WHERE organization_id IS NULL;

CREATE TABLE field_definition (
    id            UUID PRIMARY KEY,
    module_id     UUID         NOT NULL REFERENCES module_definition (id),
    api_key       VARCHAR(63)  NOT NULL CHECK (api_key ~ '^[a-z][a-z0-9_]{0,62}$'),
    label         VARCHAR(200) NOT NULL,
    description   TEXT,
    field_type    VARCHAR(30)  NOT NULL CHECK (field_type IN (
                      'TEXT', 'LONG_TEXT', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'DATETIME', 'EMAIL', 'PHONE',
                      'URL', 'CURRENCY', 'SINGLE_SELECT', 'MULTI_SELECT', 'JSON', 'FILE_REFERENCE',
                      'IMAGE_REFERENCE', 'LOOKUP')),
    status        VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE', 'DEPRECATED', 'ARCHIVED')),
    required      BOOLEAN      NOT NULL DEFAULT false,
    unique_value  BOOLEAN      NOT NULL DEFAULT false,
    read_only     BOOLEAN      NOT NULL DEFAULT false,
    sensitive     BOOLEAN      NOT NULL DEFAULT false,
    searchable    BOOLEAN      NOT NULL DEFAULT false,
    filterable    BOOLEAN      NOT NULL DEFAULT false,
    sortable      BOOLEAN      NOT NULL DEFAULT false,
    default_value JSONB,
    config        JSONB        NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(config) = 'object'),
    position      INT          NOT NULL DEFAULT 0,
    version       BIGINT       NOT NULL DEFAULT 1,
    created_at    TIMESTAMPTZ  NOT NULL,
    created_by    UUID         NOT NULL REFERENCES platform_user (id),
    updated_at    TIMESTAMPTZ  NOT NULL,
    updated_by    UUID         NOT NULL REFERENCES platform_user (id),
    CONSTRAINT uq_field_api_key UNIQUE (module_id, api_key)
);

-- Immutable snapshots of a module's complete definition, one per definition version.
CREATE TABLE module_definition_version (
    module_id  UUID        NOT NULL REFERENCES module_definition (id),
    version    BIGINT      NOT NULL,
    snapshot   JSONB       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID        NOT NULL REFERENCES platform_user (id),
    PRIMARY KEY (module_id, version)
);
