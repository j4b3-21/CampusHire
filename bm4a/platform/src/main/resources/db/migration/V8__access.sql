-- Organization roles, module permissions and record scopes (ADR-004).
CREATE TABLE org_role (
    id              UUID PRIMARY KEY,
    organization_id UUID         NOT NULL REFERENCES organization (id),
    api_key         VARCHAR(63)  NOT NULL CHECK (api_key ~ '^[a-z][a-z0-9_]{0,62}$'),
    label           VARCHAR(200) NOT NULL CHECK (length(btrim(label)) > 0),
    description     TEXT,
    -- Validated permission document: {"modules": {"<moduleKey>": {"read": ..., "create": ..., ...}}}
    permissions     JSONB        NOT NULL DEFAULT '{"modules": {}}'::jsonb CHECK (jsonb_typeof(permissions) = 'object'),
    version         BIGINT       NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      UUID         NOT NULL REFERENCES platform_user (id),
    updated_at      TIMESTAMPTZ  NOT NULL,
    updated_by      UUID         NOT NULL REFERENCES platform_user (id),
    CONSTRAINT uq_org_role_api_key UNIQUE (organization_id, api_key),
    -- Target of the tenant-preserving foreign keys below: a membership can only reference a role of its own tenant.
    CONSTRAINT uq_org_role_tenant UNIQUE (organization_id, id)
);

-- `role` (OWNER/ADMIN/MEMBER) keeps governing organization administration; `role_id` selects the org role that
-- scopes a MEMBER's record access. Deleting a role is refused while active memberships hold it; revoked
-- memberships simply lose the reference.
ALTER TABLE membership
    ADD COLUMN role_id    UUID,
    ADD COLUMN attributes JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(attributes) = 'object'),
    ADD CONSTRAINT fk_membership_org_role FOREIGN KEY (organization_id, role_id)
        REFERENCES org_role (organization_id, id) ON DELETE SET NULL (role_id);

CREATE INDEX ix_membership_role ON membership (organization_id, role_id) WHERE role_id IS NOT NULL;

-- Record ownership for OWN scopes. Defaults to the creator; trusted application code can transfer it.
ALTER TABLE record ADD COLUMN owner_user_id UUID REFERENCES platform_user (id);
UPDATE record SET owner_user_id = created_by;
CREATE INDEX ix_record_owner ON record (organization_id, module_id, owner_user_id);
