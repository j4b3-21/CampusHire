# ADR-004: Organization roles, module permissions and record scopes

Status: Accepted (2026-10-20)

## Context

ADR-002 gives every organization three membership roles (`OWNER`, `ADMIN`, `MEMBER`) and organization-wide
permissions: any member may read and write every record of every module. Real applications need more:

- a *student* may read and edit only their own profile, and never their CGPA;
- a *department coordinator* may see only the students of their department;
- a *recruiter* may see only their company's drives and applicants, without phone numbers or dates of birth;
- some modules (drives) are visible to members only in certain states (published).

## Decision

Three layers, each enforced server-side and each tested:

| Layer | Question | Enforced by | Source of truth |
|-------|----------|-------------|-----------------|
| Module permission | May this user read / write module M at all? | OpenFGA check on `module:<id>` | `org_role` rows, synced to tuples |
| Record scope | Which records of M? | SQL predicate added to every record query | `org_role.permissions` + `membership.attributes` |
| Field security | Which fields may be seen / changed? | Record API response filter and write validation | `org_role.permissions` |

### Data model (Flyway, platform)

- `org_role (id, organization_id, api_key, label, description, permissions jsonb, version, created/updated)` with
  `UNIQUE (organization_id, api_key)`.
- `membership.role_id UUID NULL REFERENCES org_role` and `membership.attributes JSONB NOT NULL DEFAULT '{}'`.
  The existing `role` column (`OWNER`/`ADMIN`/`MEMBER`) keeps governing organization administration
  (members, metadata, audit). `OWNER` and `ADMIN` always have full access to every module (scope `ALL`).
  A `MEMBER` without an org role keeps the ADR-002 behavior (full record access) for modules in `MEMBERS` access
  mode only.
- `record.owner_user_id UUID NULL REFERENCES platform_user` (defaults to the creator; can be transferred by trusted
  application code) with index `(organization_id, module_id, owner_user_id)`.
- Module setting `settings.accessMode`: `MEMBERS` (default, ADR-002 compatible) or `ROLES` (only org roles grant
  access).

### Role permissions document

```json
{
  "modules": {
    "student":     { "read": "OWN", "update": "OWN", "create": false, "delete": false,
                     "readOnlyFields": ["roll_number", "cgpa", "active_backlogs", "placement_status"] },
    "drive":       { "read": { "scope": "ALL", "where": { "status": ["published", "registration_closed",
                                                                     "in_progress", "completed"] } } },
    "application": { "read": { "scope": "ATTRIBUTE", "field": "company", "attribute": "company" },
                     "update": { "scope": "ATTRIBUTE", "field": "company", "attribute": "company" },
                     "hiddenFields": ["remarks"] }
  }
}
```

- Actions: `read`, `create`, `update`, `delete`. `read`/`update`/`delete` take a *rule*; `create` is a boolean.
- A rule is `"NONE" | "ALL" | "OWN"` or an object `{scope, field?, attribute?, where?}`:
  - `ALL` - every record; `OWN` - `owner_user_id = caller`;
  - `ATTRIBUTE` - `data ->> field` equals the caller's `membership.attributes[attribute]` (string or array of
    strings, array means "any of"). `field: "$id"` compares the record id itself (a recruiter reading their own
    company record);
  - `where` - additional constant equality/`IN` filters on fields, combined with AND.
- `readOnlyFields` - rejected with `403 field_read_only` when present in a create/update payload with a changed
  value. `hiddenFields` - removed from every response (records, versions, exports) and rejected on write.
- Validation on save: modules and fields must exist, rule shapes must be valid, `ATTRIBUTE` fields must be
  `filterable`. Invalid documents are rejected with `400 validation_failed`.

### OpenFGA model additions

```
type role
  relations
    define organization: [organization]
    define assignee: [user]

type module
  relations
    define organization: [organization]
    define reader: [user, role#assignee, organization#member]
    define writer: [user, role#assignee, organization#member]
    define can_read: reader or writer or admin from organization
    define can_write: writer or admin from organization
```

Tuples are derived from PostgreSQL and synchronized through the existing transactional outbox
(`RelationshipTuplesChanged`):

- module created in `MEMBERS` mode -> `module:<id>#reader@organization:<org>#member` and `#writer@...#member`;
  switching to `ROLES` removes them;
- role saved -> `module:<id>#reader@role:<roleId>#assignee` when `read != NONE`, `#writer@...` when any of
  `create/update/delete` is granted (and removal of stale tuples);
- membership role assigned -> `role:<roleId>#assignee@user:<userId>` (and removal on change/revocation).

### Request flow

`ModuleAccess.require(user, orgId, moduleKey, action)` (published API of the new `access` module):

1. membership active and organization active (as ADR-002, 404/403);
2. OpenFGA `check(user, can_read|can_write, module:<id>)` - fail closed (503) when unavailable;
3. returns `ModuleGrant(TenantContext, RecordScope, FieldPolicy)` computed from the role document
   (`OWNER`/`ADMIN` -> `ALL`, no field restrictions).

`RecordService` applies `RecordScope` to list, get, update, delete, restore, history and export queries as bound
SQL predicates (never string concatenation of values). A record outside the scope is indistinguishable from a
missing one (`404`).

### Published APIs for applications

- `ModuleAccess` (above) and `GET /api/v1/orgs/{orgId}/me/access` (role, attributes, per-module capabilities,
  hidden/read-only fields) so UIs can render menus and forms.
- `MembershipAdministration` (tenancy) - trusted application code can grant a membership with a role and attributes
  (e.g. CampusHire onboarding a student who signed in with their college Google account).
- `RecordOperations` (records) - trusted, tenant-scoped record writes (create/patch/transfer owner) for application
  code such as importers and app packs; same validation, uniqueness, history and audit as the API.
- Invitations: `POST /orgs/{orgId}/invitations {email, membershipRole, roleKey, attributes}` creates a single-use
  token (stored as SHA-256 hash, 7-day expiry) and emails a link; `POST /api/v1/invitations/{token}/accept`
  succeeds only for an authenticated user whose verified email equals the invitation email.

## Consequences

- Module-level decisions are relationship-based (OpenFGA), row-level decisions are set-based (SQL), which keeps list
  queries index-friendly: `ListObjects` over thousands of records is avoided entirely.
- One OpenFGA check and one indexed membership/role lookup per request; role documents are cached per organization
  and evicted on change.
- Existing BM4A tenants keep their behavior (`MEMBERS` mode).
