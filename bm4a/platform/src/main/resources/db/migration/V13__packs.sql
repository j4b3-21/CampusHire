-- Declarative application packs (modules, fields, layouts, roles, relationships, workflows, settings) installed into
-- an organization. One row per pack with the installed version; every install or upgrade is kept in the history.
CREATE TABLE installed_pack (
    organization_id   UUID         NOT NULL REFERENCES organization (id),
    pack_key          VARCHAR(63)  NOT NULL CHECK (pack_key ~ '^[a-z][a-z0-9_]{0,62}$'),
    version           INTEGER      NOT NULL CHECK (version >= 1),
    label             VARCHAR(200) NOT NULL,
    -- SHA-256 (hex) of the pack document as installed.
    definition_sha256 CHAR(64)     NOT NULL,
    installed_at      TIMESTAMPTZ  NOT NULL,
    installed_by      UUID         NOT NULL REFERENCES platform_user (id),
    PRIMARY KEY (organization_id, pack_key)
);

CREATE TABLE installed_pack_history (
    id                UUID PRIMARY KEY,
    organization_id   UUID        NOT NULL REFERENCES organization (id),
    pack_key          VARCHAR(63) NOT NULL,
    from_version      INTEGER,
    to_version        INTEGER     NOT NULL,
    status            VARCHAR(20) NOT NULL CHECK (status IN ('INSTALLED', 'UPGRADED')),
    definition_sha256 CHAR(64)    NOT NULL,
    -- The install report: what was created and what was kept as it was.
    report            JSONB       NOT NULL CHECK (jsonb_typeof(report) = 'object'),
    installed_at      TIMESTAMPTZ NOT NULL,
    installed_by      UUID        NOT NULL REFERENCES platform_user (id)
);

CREATE INDEX ix_installed_pack_history_pack ON installed_pack_history (organization_id, pack_key, installed_at DESC);
