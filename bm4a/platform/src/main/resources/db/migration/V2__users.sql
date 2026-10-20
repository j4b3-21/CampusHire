-- Internal user identities. Keycloak owns credentials; this table maps the stable external identity
-- (issuer, subject) to an internal UUID used by every other table.
CREATE TABLE platform_user (
    id            UUID PRIMARY KEY,
    issuer        TEXT        NOT NULL,
    subject       TEXT        NOT NULL,
    email         TEXT,
    display_name  TEXT,
    status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_platform_user_identity UNIQUE (issuer, subject)
);

-- Application-level profile and preferences (not identity data).
CREATE TABLE user_profile (
    user_id      UUID PRIMARY KEY REFERENCES platform_user (id),
    locale       VARCHAR(35),
    time_zone    VARCHAR(64),
    preferences  JSONB       NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(preferences) = 'object'),
    version      BIGINT      NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ NOT NULL
);
