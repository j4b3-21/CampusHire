-- Append-only audit trail. No foreign keys on purpose: audit rows must outlive the resources they describe.
CREATE TABLE audit_event (
    id               UUID PRIMARY KEY,
    occurred_at      TIMESTAMPTZ  NOT NULL,
    organization_id  UUID,
    actor_user_id    UUID,
    action           VARCHAR(100) NOT NULL,
    resource_type    VARCHAR(50)  NOT NULL,
    resource_id      VARCHAR(200),
    correlation_id   VARCHAR(64),
    details          JSONB        NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(details) = 'object')
);

CREATE INDEX ix_audit_event_org_time ON audit_event (organization_id, occurred_at DESC, id DESC);
CREATE INDEX ix_audit_event_resource ON audit_event (organization_id, resource_type, resource_id, occurred_at DESC);

CREATE FUNCTION audit_event_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only (% rejected)', TG_OP USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER trg_audit_event_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_append_only();

CREATE TRIGGER trg_audit_event_no_truncate
    BEFORE TRUNCATE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION audit_event_append_only();
