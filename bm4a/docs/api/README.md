# BM4A API guide

- Interactive documentation: `http://localhost:8080/swagger-ui.html` (OpenAPI JSON at `/v3/api-docs`). Disabled by
  default in the `prod` profile (`API_DOCS_ENABLED=true` to enable).
- Request collection for IntelliJ / VS Code REST Client: [bm4a.http](bm4a.http).
- Everything below is also exercised by `scripts/smoke-test.sh`.

## Conventions

| Topic | Rule |
|-------|------|
| Base path | `/api/v1`; tenant-scoped resources under `/api/v1/orgs/{orgId}` |
| Authentication | `Authorization: Bearer <Keycloak access token>` with audience `bm4a-api` |
| Tenant scope | The `orgId` in the path is checked against the caller's active membership on every request. Unknown and foreign organizations both return `404`. Headers/body fields never select a tenant. |
| Concurrency | Versioned resources return `ETag: "<version>"`. `PUT`, `PATCH`, `DELETE` and restore operations require `If-Match`; missing -> `428`, stale -> `412`. |
| Errors | RFC 9457 problem details: `status`, `title`, `detail`, stable `code`, optional `errors: [{field, code, message}]`, `correlationId` |
| Paging | `page` (from 0) and `size` (1-100, default 20); responses `{items, page, size, totalElements, totalPages}` |
| Time | All timestamps are UTC ISO-8601 |
| Strict JSON | Unknown properties are rejected (`400`, `unknown_property`) |
| Module permissions | Record endpoints are authorized per module and action (ADR-004): owners/admins have full access; members need the module to be in `MEMBERS` access mode or an org role granting the action. Records outside the caller's record scope return `404`; hidden fields never appear in responses. |

Common error codes: `unauthenticated` (401), `access_denied` / `organization_inactive` /
`global_module_readonly` / `field_hidden` / `field_read_only` / `scope_violation` / `email_not_verified` /
`invitation_email_mismatch` (403), `not_found` (404), `version_conflict` (412), `precondition_required` (428),
`validation_failed` (400, with `errors`), `invalid_filter` / `invalid_sort` (400), `module_exists` /
`field_exists` / `unique_violation` / `record_referenced` / `record_linked` / `cardinality_violation` /
`last_owner` / `role_exists` / `role_in_use` / `already_member` / `invitation_pending` / `invitation_used` (409),
`invitation_expired` / `invitation_revoked` (410), `authorization_unavailable` (503).

## Getting a development token

```bash
TOKEN=$(scripts/dev-token.sh alice)          # dev users: alice, bob, platform-admin (realm role bm4a-platform-admin)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/me
```

## Walkthrough: define a module and use it without writing code

```bash
API=http://localhost:8080/api/v1
H=(-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json")

# 1. Organization (the caller becomes OWNER)
ORG=$(curl -s "${H[@]}" -X POST $API/orgs -d '{"name":"Acme"}' | jq -r .id)

# 2. Module definition (see docs/examples/example-asset-module.json for every field type)
curl -s "${H[@]}" -X POST $API/orgs/$ORG/modules -d '{
  "apiKey": "asset", "labelSingular": "Asset", "labelPlural": "Assets",
  "fields": [
    {"apiKey": "name", "label": "Name", "type": "TEXT", "required": true, "searchable": true, "sortable": true},
    {"apiKey": "serial", "label": "Serial", "type": "TEXT", "unique": true, "filterable": true},
    {"apiKey": "price", "label": "Price", "type": "CURRENCY", "sortable": true, "filterable": true},
    {"apiKey": "status", "label": "Status", "type": "SINGLE_SELECT", "filterable": true,
     "config": {"options": [{"value": "in_use", "label": "In use"}, {"value": "retired", "label": "Retired"}]}}
  ]}'

# 3. Records - the same endpoint serves every module
ID=$(curl -s "${H[@]}" -X POST $API/orgs/$ORG/modules/asset/records \
  -d '{"data": {"name": "Laptop", "serial": "LAP-1", "price": {"amount": "999.5", "currency": "USD"}, "status": "in_use"}}' | jq -r .id)

# Partial update (JSON Merge Patch) with optimistic concurrency
curl -s "${H[@]}" -X PATCH -H 'If-Match: "1"' $API/orgs/$ORG/modules/asset/records/$ID -d '{"data": {"status": "retired"}}'

# Query: filter, sort, search, paging
curl -s "${H[@]}" "$API/orgs/$ORG/modules/asset/records?filter%5Bstatus%5D=retired&filter%5Bprice%5D%5Bgte%5D=500&sort=-price&q=lap&size=10"

# Soft delete, trash, restore, history, revert
curl -s "${H[@]}" -X DELETE -H 'If-Match: "2"' $API/orgs/$ORG/modules/asset/records/$ID
curl -s "${H[@]}" "$API/orgs/$ORG/modules/asset/records?deleted=true"
curl -s "${H[@]}" -X POST -H 'If-Match: "3"' $API/orgs/$ORG/modules/asset/records/$ID/restore
curl -s "${H[@]}" $API/orgs/$ORG/modules/asset/records/$ID/versions
curl -s "${H[@]}" -X POST -H 'If-Match: "4"' $API/orgs/$ORG/modules/asset/records/$ID/versions/1/restore
```

## Endpoint reference

| Area | Method and path | Permission |
|------|-----------------|------------|
| Me | `GET /me`, `PATCH /me/profile` | authenticated |
| Organizations | `POST /orgs`, `GET /orgs` | authenticated |
| | `GET /orgs/{orgId}` | ORGANIZATION_VIEW (member) |
| | `PATCH /orgs/{orgId}` (If-Match) | ORGANIZATION_UPDATE (admin) |
| | `DELETE /orgs/{orgId}` (If-Match, archives) | ORGANIZATION_ARCHIVE (owner) |
| Members | `GET /orgs/{orgId}/members` | member |
| | `POST /orgs/{orgId}/members` `{userId, role, roleKey?, attributes?}`, `PATCH .../{userId}` `{role?, roleKey?, attributes?}`, `DELETE .../{userId}` | MEMBERS_MANAGE (admin; owner for admin/owner roles and for editing admins/owners); members may remove themselves |
| Invitations | `POST /orgs/{orgId}/invitations` `{email, membershipRole, roleKey?, attributes?}`, `GET /orgs/{orgId}/invitations[?status=]`, `DELETE .../{id}` (revoke) | MEMBERS_MANAGE (owner for ADMIN/OWNER invitations) |
| | `GET /invitations/{token}` (preview), `POST /invitations/{token}/accept` | authenticated; accept requires the invited, verified e-mail |
| Org roles | `GET`/`POST /orgs/{orgId}/roles`, `GET .../{roleKey}`, `PUT`/`DELETE .../{roleKey}` (If-Match) | METADATA_MANAGE (admin) |
| My access | `GET /orgs/{orgId}/me/access` | member |
| Modules | `GET /orgs/{orgId}/modules[?includeArchived=true]`, `GET .../{moduleKey}`, `GET .../versions[/{v}]` | member |
| | `POST /orgs/{orgId}/modules`, `PATCH`/`DELETE .../{moduleKey}`, `POST .../{moduleKey}/restore`, `PUT .../{moduleKey}/layout` | METADATA_MANAGE (admin) |
| Fields | `GET .../{moduleKey}/fields[/{fieldKey}]` | member |
| | `POST .../fields`, `PATCH .../fields/{fieldKey}`, `POST .../fields/{fieldKey}/deprecate\|archive\|activate` | METADATA_MANAGE |
| Records | `GET .../{moduleKey}/records[/{id}]`, `GET .../{id}/versions[/{v}]` | module READ (scope applies) |
| | `POST .../records` | module CREATE |
| | `PUT`/`PATCH .../{id}`, `POST .../{id}/versions/{v}/restore` | module UPDATE (scope applies before and after the change) |
| | `DELETE .../{id}`, `POST .../{id}/restore` | module DELETE (scope applies) |
| Relationships | `GET /orgs/{orgId}/relationships[/{key}]` | member |
| | `POST /orgs/{orgId}/relationships`, `DELETE .../{key}` (If-Match, archives) | METADATA_MANAGE |
| | `POST .../{key}/links` `{sourceRecordId, targetRecordId}`, `DELETE .../{key}/links/{linkId}` | UPDATE on the source record, READ on the target record |
| | `GET /orgs/{orgId}/modules/{moduleKey}/records/{id}/related/{key}[?direction=incoming]` | READ on both modules; the related side is scoped and field-filtered |
| Audit | `GET /orgs/{orgId}/audit-events[?action=&resourceType=&resourceId=&actorUserId=]` | AUDIT_VIEW (admin) |
| Notifications | `GET /notifications[?unread=true]`, `GET /notifications/unread-count`, `POST /notifications/{id}/read`, `POST /notifications/read-all`, `GET /notifications/stream` (SSE) | authenticated (own notifications only) |
| Platform | `POST /platform/orgs/{orgId}/suspend\|reactivate`, `/platform/modules/**` (global modules) | realm role `bm4a-platform-admin` |

## Record filters

`filter[field]=value` (equality) or `filter[field][op]=value`. Only fields with `filterable: true`; sorting only on
`sortable: true` fields plus `created_at`/`updated_at`; `q` searches `searchable: true` fields.

| Field types | Operators |
|-------------|-----------|
| INTEGER, DECIMAL, CURRENCY (amount), DATE, DATETIME | `eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `exists` (CURRENCY: range only) |
| TEXT, LONG_TEXT, EMAIL, PHONE, URL | `eq`, `ne`, `contains` (case-insensitive), `in` (comma-separated), `exists` |
| SINGLE_SELECT, LOOKUP | `eq`, `ne`, `in`, `exists` |
| MULTI_SELECT | `contains`, `exists` |
| BOOLEAN | `eq`, `ne`, `exists` |

Field types and their canonical JSON encoding are listed in
[ADR-001](../architecture/ADR-001-dynamic-record-storage.md#canonical-value-encoding).

Fields hidden from the caller (see below) cannot be filtered, sorted or searched.

## Roles, record scopes and field security

Design: [ADR-004](../architecture/ADR-004-roles-and-scoped-permissions.md).

- Module setting `settings.accessMode`: `MEMBERS` (default; every member may use the module unless their org role
  configures it) or `ROLES` (only org roles listing the module grant access to members).
- Org role: `{apiKey, label, description?, permissions}`. The permission document, returned in canonical form:

```json
{"modules": {
  "student": {"read": "OWN", "create": false, "update": "OWN", "delete": "NONE",
              "readOnlyFields": ["cgpa"], "hiddenFields": ["remarks"]},
  "application": {"read": {"scope": "ATTRIBUTE", "field": "company", "attribute": "company"}},
  "company": {"read": {"scope": "ATTRIBUTE", "field": "$id", "attribute": "company"}},
  "drive": {"read": {"scope": "ALL", "where": {"stage": ["published", "completed"]}}}
}}
```

  Rules are `NONE | ALL | OWN` or `{scope, field?, attribute?, where?}`; `ATTRIBUTE` compares a filterable field (or
  `$id`, the record ID) with the member's attribute (string or array = any of; missing = no records); `where` adds
  constant equality filters on filterable fields. `create` is a boolean. Unknown modules/fields and invalid shapes are
  `400 validation_failed` with `permissions.modules.<key>...` error paths. Roles held by active members or pending
  invitations cannot be deleted (`409 role_in_use`).
- Records carry `ownerUserId` (the creator; transferable by trusted application code) for `OWN` scopes. Updates that
  would move a record outside the caller's update scope fail with `403 scope_violation`.
- Writes containing hidden fields fail with `403 field_hidden`; changing a read-only field fails with
  `403 field_read_only` (resending the unchanged value is fine; `PUT` keeps omitted read-only and hidden values).
- Membership attributes: an object of strings or string arrays, e.g. `{"department": "<uuid>", "company": ["<uuid>"]}`
  (max 20 keys, 100 values each). `PATCH .../members/{userId}` replaces them; `"roleKey": null` removes the org role.

`GET /orgs/{orgId}/me/access`:

```json
{"organizationId": "...", "membershipRole": "MEMBER", "admin": false,
 "role": {"key": "student", "label": "Student"},
 "attributes": {"student": "...", "department": "..."},
 "modules": {"student": {"read": true, "create": false, "update": true, "delete": false, "readScope": "OWN",
                         "hiddenFields": ["remarks"], "readOnlyFields": ["cgpa"]}}}
```

`role` is `null` without an org role; `modules` lists every active module the caller can read (`readScope`: `ALL`,
`OWN` or `ATTRIBUTE`).

## Invitations

```bash
# Create (MEMBERS_MANAGE): the response is the only place the accept link is returned.
curl -s "${H[@]}" -X POST $API/orgs/$ORG/invitations \
  -d '{"email": "hr@acme.test", "membershipRole": "MEMBER", "roleKey": "recruiter", "attributes": {"company": "<id>"}}'
# -> 201 {id, email, membershipRole, roleKey, attributes, status: "PENDING", expiresAt, createdAt, createdBy,
#         acceptUrl: "http://localhost:3000/invite/<token>"}

curl -s "${H[@]}" "$API/orgs/$ORG/invitations?status=PENDING"     # PENDING | EXPIRED | ACCEPTED | REVOKED
curl -s "${H[@]}" -X DELETE $API/orgs/$ORG/invitations/$ID        # revoke (204)

# Invitee (signed in):
curl -s "${H[@]}" $API/invitations/$TOKEN
# -> {organizationId, organizationName, membershipRole, roleKey, roleLabel, email: "h***@acme.test", status, expiresAt}
curl -s "${H[@]}" -X POST $API/invitations/$TOKEN/accept
# -> {userId, email, displayName, role, roleKey, attributes, status, createdAt, updatedAt}
```

Tokens are 32 random bytes (base64url), stored only as SHA-256 hashes, valid for 7 days and single use. Accepting
requires the token's `email_verified` claim and the same e-mail (case-insensitive): otherwise `403
email_not_verified` / `invitation_email_mismatch`; reused `409 invitation_used`; expired or revoked
`410 invitation_expired` / `invitation_revoked`. The accept link base is `bm4a.app.base-url` (`APP_BASE_URL`, default
`http://localhost:3000`). After commit the invitation e-mail (template `invitation`) is queued in the e-mail outbox
and sent by the notifications worker ([EMAIL.md](../operations/EMAIL.md)); the link is never logged.

## Notifications

Design: [ADR-007](../architecture/ADR-007-workflow-notifications-and-caching.md). Every endpoint returns only the
caller's own notifications, across all organizations; another user's notification is `404`.

```bash
curl -s "${H[@]}" "$API/notifications?unread=true&page=0&size=20"   # newest first; unread optional
# -> {items: [{id, organizationId, type, title, body, link, data, read, readAt, createdAt}], page, size,
#     totalElements, totalPages}
curl -s "${H[@]}" $API/notifications/unread-count                     # -> {"count": 3}
curl -s "${H[@]}" -X POST $API/notifications/$ID/read                 # -> the notification (idempotent)
curl -s "${H[@]}" -X POST $API/notifications/read-all                 # -> {"updated": 3}
```

`organizationId` is `null` for platform-level notifications. `link` is an absolute path in the web application
(`/app/<orgId>/...`) or an `http(s)` URL; `title` and `body` are plain text (render as text, never as HTML); `data` is
an object of extra values set by the sender.

Real time: `GET /notifications/stream` is a Server-Sent Events stream (`text/event-stream`) authenticated with the
normal `Authorization: Bearer` header, so browsers need a fetch-based SSE client (`EventSource` cannot send headers).
Reconnect when the stream ends (it is closed after 30 minutes, on deploys, or when the client falls behind) and
re-fetch the list after reconnecting. At most 5 streams per user per API replica; opening a sixth closes the oldest.

```text
retry:5000
:connected

event:unread-count
data:{"count":2}

event:notification
id:6f1c...-uuid
data:{"id":"6f1c...","organizationId":"...","type":"drive.published","title":"New drive","body":"...","link":"/app/...","data":{},"read":false,"readAt":null,"createdAt":"2026-10-20T10:00:00Z"}

event:unread-count
data:{"count":3}

:keep-alive
```

- `unread-count` comes first on every connection and after every change (new notification, read, read all - also
  from other tabs and devices).
- `notification` carries the same JSON as a list item; the SSE `id` is the notification ID.
- Comment lines (`:keep-alive` every 25 s) only keep proxies from closing idle connections. The response sets
  `X-Accel-Buffering: no`; other proxies must not buffer `/api/v1/notifications/stream`.

Backend modules send notifications with `Notifier.notify(organizationId, userIds, NotificationMessage, channels)`
(`IN_APP`, `EMAIL`) and arbitrary e-mail with `Notifier.sendEmail(organizationId, to, EmailMessage)`; rows are written
in the caller's transaction and delivered after commit (real-time push across replicas via Redis pub/sub, e-mail via
the outbox worker).

## Workflows

Design: [ADR-007](../architecture/ADR-007-workflow-notifications-and-caching.md). A workflow is a state machine on one
`SINGLE_SELECT` field of a module (one per module and organization).

| Method and path | Permission |
|-----------------|------------|
| `GET /orgs/{orgId}/modules/{moduleKey}/workflow` | member |
| `PUT .../workflow` `{field, definition}` (no If-Match: create, 201; If-Match: replace), `DELETE .../workflow` (If-Match) | METADATA_MANAGE |
| `GET .../records/{id}/transitions[?page=&size=]` (history, newest first) | module READ (scope applies) |
| `GET .../records/{id}/transitions/available` | module READ (scope applies) |
| `POST .../records/{id}/transitions` `{to, comment?}` (If-Match) | module UPDATE (scope and field policy apply) |
| `POST /orgs/{orgId}/modules/{moduleKey}/transitions/bulk` `{recordIds (1-500), to, comment?}` | module UPDATE (scope applies per record) |

Definition (the `GET`/`PUT` response wraps it as
`{id, module, field, version, definition, createdAt, createdBy, updatedAt, updatedBy}`, ETag = `version`):

```json
{"initial": "applied",
 "states": [{"key": "applied", "label": "Applied", "category": "OPEN", "color": "slate"},
            {"key": "offered", "label": "Offered", "category": "WON", "color": "emerald"}],
 "transitions": [{"key": "offer", "label": "Mark offered", "from": ["applied"], "to": "offered",
                  "roles": ["tpo", "recruiter"], "requireComment": false}]}
```

- `category`: `OPEN | IN_PROGRESS | WON | LOST`; `color` is optional (`^[A-Za-z0-9#-]{1,32}$`).
- Validation (`400 validation_failed`, paths like `definition.transitions[2].from`): the field is an active, writable
  `SINGLE_SELECT` field whose **default value is the initial state**; every state is an active option of the field;
  state and transition keys are unique; transitions reference known states and never lead to one of their own `from`
  states; `roles` are org role keys or `["*"]` (every member); unknown properties are rejected.

Enforcement applies to every write path (record API `POST`/`PUT`/`PATCH`/version restore, transitions API, imports,
trusted application code):

- new records must start in the initial state (the field default provides it);
- changing the field requires a defined transition from the current state; members need their org role (or `*`) in
  `roles`, organization admins and trusted code may use every defined transition (and nothing else); a record without
  a state may only be moved to the initial state; the field cannot be cleared - otherwise
  `409 transition_not_allowed`, whose `detail` lists the transitions available to the caller, e.g.
  `Moving from 'review' to 'approved' is not allowed. Allowed from 'review': reject (to 'rejected').`;
- transitions with `requireComment: true` only through `POST .../transitions` with a non-blank comment
  (`409 comment_required` otherwise, including plain `PATCH`).

Every change of the field writes exactly one history entry (the record's creation included, with `fromState: null`):

```json
{"id": "...", "recordVersion": 4, "fromState": "review", "toState": "rejected", "transitionKey": "reject",
 "comment": "Duplicate", "actorUserId": "...", "createdAt": "2026-10-20T10:15:00Z"}
```

`POST .../records/{id}/transitions` returns `{record: {id, module, version, data, ...}, transition: {...history
entry}}` with the record's new ETag. `GET .../transitions/available` (for buttons and boards; empty when the caller
cannot update the record or the field):

```json
{"recordId": "...", "version": 3, "field": "stage", "currentState": "review",
 "transitions": [{"key": "reject", "label": "Reject", "to": "rejected", "toLabel": "Rejected", "category": "LOST",
                  "color": "red", "requireComment": false}]}
```

Bulk transitions run each record in its own transaction and always answer `200`:

```json
{"results": [{"recordId": "...", "ok": true, "version": 2},
             {"recordId": "...", "ok": false, "error": {"code": "not_found", "message": "Record not found"}},
             {"recordId": "...", "ok": false, "error": {"code": "transition_not_allowed", "message": "..."}}],
 "succeeded": 1, "failed": 2}
```

Java API (`com.bm4a.platform.modules.workflow`): `Workflows.find/create/transition` for application code and the
`WorkflowTransitioned(organizationId, moduleId, moduleKey, recordId, fromState, toState, actorUserId, comment,
transitionKey)` event, published in the record's transaction for transitions of existing records
(`@ApplicationModuleListener`s receive it after commit).

## Packs

A pack is a declarative, versioned application definition installed into an organization, e.g. CampusHire's
`campushire-pack.json`:

```json
{"key": "campushire", "version": 1, "label": "CampusHire", "description": "...",
 "settings": {"campushire": {"policy": {"maxOffersPerStudent": 1}}},
 "modules": [{"apiKey": "student", "labelSingular": "Student", "labelPlural": "Students",
              "settings": {"accessMode": "ROLES"}, "fields": [...], "layout": {"sections": [...]}}],
 "roles": [{"apiKey": "student", "label": "Student", "description": "...", "permissions": {"modules": {...}}}],
 "relationships": [{"apiKey": "mentors", "label": "Mentors", "sourceModule": "student", "targetModule": "contact",
                    "cardinality": "MANY_TO_MANY", "onDelete": "DETACH"}],
 "workflows": [{"module": "application", "field": "stage", "definition": {...}}]}
```

Modules, fields, roles and relationships have the shape of the corresponding create requests; unknown properties
anywhere are rejected (`400 validation_failed` with paths such as `modules[3].fields[2].bogus`). Fields without a
`position` keep the pack order.

| Method and path | Permission |
|-----------------|------------|
| `GET /orgs/{orgId}/packs` -> `[{packKey, version, label, definitionSha256, installedAt, installedBy}]` | member |
| `POST /orgs/{orgId}/packs/install` (body: the pack, at most 512 KiB, else `413 payload_too_large`) | METADATA_MANAGE |

Installation is one transaction, serialized per organization, and purely additive: (1) missing modules without their
lookup fields, (2) missing fields (lookups included) and missing options of existing select fields, (3) layouts of
modules created now or still without a layout, (4) missing roles, (5) missing relationships, (6) missing workflows,
(7) organization settings deep-merged (existing values win), (8) the installed version. Existing fields, layouts,
role documents and workflows are never changed. Any failure rolls the whole install back; errors carry the pack path
(`modules[2].fields[6].config.targetModuleKey`, `roles[1].permissions.modules.nope`). Installing the installed version
again is a no-op (`UP_TO_DATE`); a lower version is `409 pack_downgrade`.

```json
{"packKey": "campushire", "version": 2, "status": "UPGRADED", "previousVersion": 1,
 "created": {"modules": ["notice"], "fields": ["notice.title", "student.hostel", "notice.batch"],
             "options": ["batch.program:bsc"], "layouts": ["notice"], "roles": ["alumni"], "relationships": [],
             "workflows": [], "settings": ["campushire.policy.allowOffCampus"]},
 "skipped": [{"type": "role", "key": "student", "reason": "exists"},
             {"type": "workflow", "key": "application", "reason": "exists"}]}
```

`status` is `INSTALLED | UPGRADED | UP_TO_DATE`; `skipped[].reason` is `exists`, `type_differs` (a pack field whose
existing field has another type) or `changed_without_version_bump` (same version, different document).

Applications install their pack from code with `PackInstaller.install(organizationId, actorUserId, packJson)`. The
installer builds on published, permission-free ports that other trusted code can use as well:
`ModuleDefinitions` (metadata), `RoleDefinitions` (access), `RelationshipDefinitions` (relationships),
`OrganizationSettings` (tenancy) and `Workflows.create` (workflow).

## Upgrading databases created before ADR-004

Modules created before migration V8 have no OpenFGA module tuples. Start the platform once with
`BM4A_SYNC_MODULE_TUPLES=true` (property `bm4a.metadata.sync-module-tuples-on-startup`) to write them; the operation
is idempotent.
