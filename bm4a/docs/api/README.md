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
`http://localhost:3000`). Mail is sent after commit by the application's `InvitationMailer` bean; without one the
platform only logs that an invitation was created (never the link).

## Upgrading databases created before ADR-004

Modules created before migration V8 have no OpenFGA module tuples. Start the platform once with
`BM4A_SYNC_MODULE_TUPLES=true` (property `bm4a.metadata.sync-module-tuples-on-startup`) to write them; the operation
is idempotent.

## Files

Design: [ADR-005](../architecture/ADR-005-files-and-bulk-data-exchange.md).

| Method and path | Permission |
|-----------------|------------|
| `POST /orgs/{orgId}/files` (multipart part `file`, max 25 MB) | active member |
| `GET /orgs/{orgId}/files/{id}` (content), `GET .../{id}/meta` | uploader, OWNER/ADMIN, or a member who can read an active record referencing the file |

```bash
curl -s -H "Authorization: Bearer $TOKEN" -F "file=@resume.pdf" $API/orgs/$ORG/files
# -> 201, Location: /api/v1/orgs/<org>/files/<id>
#    {id, name: "resume.pdf", contentType: "application/pdf", sizeBytes, sha256, createdAt, createdBy}
curl -s -H "Authorization: Bearer $TOKEN" -OJ $API/orgs/$ORG/files/$FILE_ID
```

- The type is detected from the content (magic bytes), never from the name or the declared type. Allowed: PDF, PNG,
  JPEG, WebP, XLSX, DOCX (no macro-enabled packages) and UTF-8 text (`text/csv` when the name ends in `.csv` or the
  declared type is a CSV type, else `text/plain`). Anything else is `415 unsupported_file_type`; empty files
  `400 empty_file`; larger than 25 MB `413 file_too_large`; object store down `503 file_storage_unavailable`.
- Objects are stored under `<orgId>/<yyyy>/<mm>/<uuid>`; `name` is the sanitized original name (display only).
- Downloads are `Content-Disposition: attachment; filename="<ascii>"; filename*=UTF-8''<name>`,
  `X-Content-Type-Options: nosniff`, `Cache-Control: private, no-store`.
- Reading through a record: the caller must be able to read the module (ADR-004), the record must be active and
  within their record scope, and the referencing `FILE_REFERENCE` / `IMAGE_REFERENCE` field must not be hidden from
  them. Everyone else, including members of other organizations, gets `404`.
- Record writes reference files as `{"fileId": "<id>", "name"?, "contentType"?}`. The file must exist in the same
  organization (`400 validation_failed`, code `invalid_reference`); `IMAGE_REFERENCE` fields need an `image/*` file
  (code `not_an_image`).

Configuration: `FILES_STORAGE` (`filesystem` default, or `s3`), `FILES_LOCAL_DIR` (default `<java.io.tmpdir>/bm4a-files`),
`S3_ENDPOINT` (e.g. `http://minio:9000`; empty for AWS), `S3_REGION` (`us-east-1`), `S3_BUCKET` (`bm4a-files`, created
when missing), `S3_ACCESS_KEY` / `S3_SECRET_KEY` (empty: AWS default credential chain), `S3_PATH_STYLE` (`true`).
The filesystem adapter suits a single node only.
