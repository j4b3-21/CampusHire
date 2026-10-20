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

Common error codes: `unauthenticated` (401), `access_denied` / `organization_inactive` /
`global_module_readonly` (403), `not_found` (404), `version_conflict` (412), `precondition_required` (428),
`validation_failed` (400, with `errors`), `invalid_filter` / `invalid_sort` (400), `module_exists` /
`field_exists` / `unique_violation` / `record_referenced` / `record_linked` / `cardinality_violation` /
`last_owner` (409), `authorization_unavailable` (503).

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
| | `POST /orgs/{orgId}/members` `{userId, role}`, `PATCH .../{userId}` `{role}`, `DELETE .../{userId}` | MEMBERS_MANAGE (admin; owner for admin/owner roles); members may remove themselves |
| Modules | `GET /orgs/{orgId}/modules[?includeArchived=true]`, `GET .../{moduleKey}`, `GET .../versions[/{v}]` | member |
| | `POST /orgs/{orgId}/modules`, `PATCH`/`DELETE .../{moduleKey}`, `POST .../{moduleKey}/restore`, `PUT .../{moduleKey}/layout` | METADATA_MANAGE (admin) |
| Fields | `GET .../{moduleKey}/fields[/{fieldKey}]` | member |
| | `POST .../fields`, `PATCH .../fields/{fieldKey}`, `POST .../fields/{fieldKey}/deprecate\|archive\|activate` | METADATA_MANAGE |
| Records | `GET .../{moduleKey}/records[/{id}]`, `GET .../{id}/versions[/{v}]` | RECORDS_READ (member) |
| | `POST .../records`, `PUT`/`PATCH`/`DELETE .../{id}`, `POST .../{id}/restore`, `POST .../{id}/versions/{v}/restore` | RECORDS_WRITE (member) |
| Relationships | `GET /orgs/{orgId}/relationships[/{key}]` | member |
| | `POST /orgs/{orgId}/relationships`, `DELETE .../{key}` (If-Match, archives) | METADATA_MANAGE |
| | `POST .../{key}/links` `{sourceRecordId, targetRecordId}`, `DELETE .../{key}/links/{linkId}` | RECORDS_WRITE |
| | `GET /orgs/{orgId}/modules/{moduleKey}/records/{id}/related/{key}[?direction=incoming]` | RECORDS_READ |
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
