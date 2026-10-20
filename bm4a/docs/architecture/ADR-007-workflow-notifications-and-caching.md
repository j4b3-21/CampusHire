# ADR-007: Workflow engine, notifications and metadata caching

Status: Accepted (2026-10-20)

## Workflow (`workflow` module)

A workflow attaches a state machine to one `SINGLE_SELECT` field of a module (for example `application.status`).

- `workflow_definition (id, organization_id, module_id, field_key, definition jsonb, version, ...)`,
  one per module. The definition lists states (`key`, `label`, `category`: `OPEN | IN_PROGRESS | WON | LOST`,
  `color`) and transitions (`key`, `label`, `from: [state]`, `to`, `roles: [roleKey] | ["*"]`,
  `requireComment`). States must be options of the field.
- Enforcement is inside the record write path through a `RecordWriteGuard` SPI published by `records`: any create
  or update that changes the workflow field must follow a transition allowed for the caller's role (admins may use
  every transition). Violations return `409 transition_not_allowed`. New records start in the initial state.
- `POST /orgs/{orgId}/modules/{key}/records/{id}/transitions {to, comment}` (If-Match) and
  `POST /orgs/{orgId}/modules/{key}/transitions/bulk {recordIds, to, comment}` (per-record result list, max 500).
- `workflow_transition (record_id, organization_id, from_state, to_state, actor, comment, at)` keeps the history
  (`GET .../records/{id}/transitions`). A `WorkflowTransitioned` event is published after commit.

## Notifications (`notifications` module)

- `Notifier` API: `notify(organizationId, recipients, type, title, body, link, channels)`.
- In-app: `notification` table (index `(user_id, created_at DESC) WHERE read_at IS NULL`), `GET /api/v1/notifications`,
  mark read / read all, unread count, and a Server-Sent Events stream `GET /api/v1/notifications/stream`.
  Each API replica keeps its own SSE connections; new notifications are fanned out through Redis pub/sub so a user
  connected to replica B sees a notification created on replica A.
- Email: `email_outbox` rows written in the business transaction, sent by a scheduled worker
  (`FOR UPDATE SKIP LOCKED`, batches of 50, exponential backoff, max 8 attempts) through SMTP (Mailpit in
  development, the college SMTP relay or Google Workspace in production). HTML templates with a plain-text part.

## Metadata caching

Module schemas are read on every record request. They are cached in Redis (JSON, 10 minute TTL) under keys that
include the organization and module key, and evicted after commit whenever the module, a field or the layout
changes (definition version bump). Redis is never a source of truth: a cache miss or a Redis outage falls back to
PostgreSQL.

## Consequences

- Process rules (who may move an application from `interview` to `offered`) are data, editable per organization,
  and still enforced server-side for every write path (UI, API, imports).
- Email delivery is decoupled from requests and survives SMTP outages; in-app notifications are real time across
  replicas.
