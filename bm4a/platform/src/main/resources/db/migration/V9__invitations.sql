-- E-mail invitations into an organization (ADR-004). Only the SHA-256 hash of the single-use token is stored.
CREATE TABLE invitation (
    id              UUID PRIMARY KEY,
    organization_id UUID         NOT NULL REFERENCES organization (id),
    email           VARCHAR(254) NOT NULL CHECK (email = lower(email)),
    membership_role VARCHAR(20)  NOT NULL CHECK (membership_role IN ('OWNER', 'ADMIN', 'MEMBER')),
    role_id         UUID,
    attributes      JSONB        NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(attributes) = 'object'),
    token_hash      CHAR(64)     NOT NULL,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED')),
    expires_at      TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      UUID         NOT NULL REFERENCES platform_user (id),
    accepted_at     TIMESTAMPTZ,
    accepted_by     UUID REFERENCES platform_user (id),
    revoked_at      TIMESTAMPTZ,
    revoked_by      UUID REFERENCES platform_user (id),
    CONSTRAINT uq_invitation_token UNIQUE (token_hash),
    -- Pending, unexpired invitations count as role assignments (role deletion is refused); others lose the reference.
    CONSTRAINT fk_invitation_org_role FOREIGN KEY (organization_id, role_id)
        REFERENCES org_role (organization_id, id) ON DELETE SET NULL (role_id)
);

CREATE INDEX ix_invitation_org_created ON invitation (organization_id, created_at DESC, id);
CREATE INDEX ix_invitation_pending_email ON invitation (organization_id, email) WHERE status = 'PENDING';
CREATE INDEX ix_invitation_role ON invitation (organization_id, role_id) WHERE role_id IS NOT NULL;
