-- Organizations (tenants) and memberships. Membership rows are the source of truth for who belongs to an
-- organization; OpenFGA holds a derived copy as relationship tuples.
CREATE TABLE organization (
    id          UUID PRIMARY KEY,
    name        VARCHAR(200) NOT NULL CHECK (length(btrim(name)) > 0),
    status      VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    settings    JSONB        NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(settings) = 'object'),
    version     BIGINT       NOT NULL DEFAULT 1,
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  UUID         NOT NULL REFERENCES platform_user (id),
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  UUID         NOT NULL REFERENCES platform_user (id)
);

CREATE TABLE membership (
    organization_id UUID        NOT NULL REFERENCES organization (id),
    user_id         UUID        NOT NULL REFERENCES platform_user (id),
    role            VARCHAR(20) NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER')),
    status          VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at      TIMESTAMPTZ NOT NULL,
    created_by      UUID        NOT NULL REFERENCES platform_user (id),
    updated_at      TIMESTAMPTZ NOT NULL,
    updated_by      UUID        NOT NULL REFERENCES platform_user (id),
    PRIMARY KEY (organization_id, user_id)
);

-- "Which organizations does this user belong to?" (tenant context resolution and GET /orgs).
CREATE INDEX ix_membership_user_active ON membership (user_id) WHERE status = 'ACTIVE';
