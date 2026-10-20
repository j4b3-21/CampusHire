-- In-app notifications and the e-mail outbox (ADR-007).

-- organization_id NULL marks a platform-level notification (not tied to a tenant).
CREATE TABLE notification (
    id              UUID PRIMARY KEY,
    organization_id UUID REFERENCES organization (id),
    user_id         UUID          NOT NULL REFERENCES platform_user (id),
    type            VARCHAR(100)  NOT NULL,
    title           VARCHAR(200)  NOT NULL,
    body            VARCHAR(4000),
    link            VARCHAR(2000),
    data            JSONB         NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(data) = 'object'),
    read_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL
);

CREATE INDEX ix_notification_user_created ON notification (user_id, created_at DESC, id DESC);
CREATE INDEX ix_notification_user_unread ON notification (user_id, created_at DESC, id DESC) WHERE read_at IS NULL;

-- Written in the business transaction, delivered by the e-mail worker. SENDING rows whose next_attempt_at passed
-- belong to a worker that died mid-send and are claimed again. Bodies may contain one-time links, so they are removed
-- once the message is sent; recipient and subject stay for audit.
CREATE TABLE email_outbox (
    id              UUID PRIMARY KEY,
    organization_id UUID REFERENCES organization (id),
    to_address      VARCHAR(254)  NOT NULL,
    subject         VARCHAR(500)  NOT NULL,
    html_body       TEXT,
    text_body       TEXT,
    status          VARCHAR(20)   NOT NULL CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED')),
    attempts        INT           NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ   NOT NULL,
    last_error      VARCHAR(1000),
    created_at      TIMESTAMPTZ   NOT NULL,
    sent_at         TIMESTAMPTZ,
    CONSTRAINT ck_email_outbox_sent_bodies CHECK (status <> 'SENT' OR (html_body IS NULL AND text_body IS NULL))
);

CREATE INDEX ix_email_outbox_claim ON email_outbox (next_attempt_at) WHERE status IN ('PENDING', 'SENDING');
CREATE INDEX ix_email_outbox_org_created ON email_outbox (organization_id, created_at DESC);
