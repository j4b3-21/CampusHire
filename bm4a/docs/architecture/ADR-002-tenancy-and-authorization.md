# ADR-002: Tenancy, identity and authorization

Status: Accepted (2026-10-20)

## Context

BM4A is multi-tenant. Every request must be attributed to a stable user and a verified organization, and every
protected operation must be authorized server-side. Keycloak (OIDC) and OpenFGA (relationship-based access
control) are already part of the infrastructure.

## Decisions

### 1. Identity

- The API is an OAuth2 resource server. Tokens are validated for **signature** (RS256 via the realm JWKS),
  **issuer** (`bm4a.security.jwt.issuer-uri`), **expiry/not-before** (60 s skew) and **audience**
  (`bm4a.security.jwt.audience`, default `bm4a-api`). There is no permissive or "test" mode in production code;
  tests sign real tokens with a test key and run them through the same validator chain.
- The issuer used for claim validation and the JWKS URL are configured separately, so a containerized platform
  can fetch keys from `http://keycloak:8080/...` while still requiring `iss = http://localhost:2346/realms/bm4a`.
- The internal user is keyed by `(issuer, subject)`; e-mail and username are informational and may change.
  Users are provisioned just-in-time on their first authenticated request. A user row can be `SUSPENDED`
  locally, which blocks API access even with a valid token.
- Keycloak owns credentials, MFA, social login (Google), SSO and account lifecycle. BM4A stores no passwords.
- Platform-wide administration (global modules, organization suspension) requires the Keycloak realm role
  `bm4a-platform-admin`.

### 2. Tenant context

- Tenant-scoped endpoints carry the organization in the path: `/api/v1/orgs/{orgId}/...`.
- The path value is **only a claim**. `TenantAccessService.require(user, orgId, permission)`:
  1. loads the caller's membership for `orgId` from PostgreSQL; missing or `REVOKED` -> **404** (the
     organization's existence is not disclosed);
  2. rejects organizations that are not `ACTIVE` -> 403 `organization_inactive`;
  3. asks the authorization service whether the user holds the permission -> 403 `access_denied` if not;
  4. returns an immutable `TenantContext(organizationId, userId, role)`.
- `TenantContext` is passed **explicitly** to application services. There is no ThreadLocal or other global
  mutable tenant state, so asynchronous work cannot accidentally inherit or lose a tenant. Background work and
  events carry `organizationId` explicitly in their payloads.
- Headers or body fields such as `organizationId`/`tenantId` are never read for scoping; record payloads
  cannot contain system fields.
- Every tenant-owned table has `organization_id NOT NULL` (metadata allows `NULL` only for explicitly
  platform-global modules). Every query includes `organization_id` in its `WHERE` clause, and lookups by id are
  `WHERE id = ? AND organization_id = ?`, which turns foreign IDs into 404s (no IDOR).

### 3. Authorization (OpenFGA)

Model (`platform/src/main/resources/openfga/model.fga`, JSON twin `model.json`):

```
model
  schema 1.1
type user
type organization
  relations
    define owner: [user]
    define admin: [user] or owner
    define member: [user] or admin
    define can_view: member
    define can_update: admin
    define can_archive: owner
    define can_manage_members: admin
    define can_manage_metadata: admin
    define can_read_records: member
    define can_write_records: member
    define can_view_audit: admin
```

- Code depends only on the `AuthorizationService` port (`check(userId, Permission, organizationId)`). The
  OpenFGA SDK is confined to `modules.authorization.internal`.
- **Source of truth:** PostgreSQL `membership` rows. OpenFGA holds a derived copy as tuples
  `organization:<orgId>#<owner|admin|member>@user:<userId>`.
- **Synchronization:** membership changes publish a `RelationshipTuplesChanged` event inside the business
  transaction. A transactional event listener applies the tuple writes/deletes **after commit**, in the request
  thread, so the caller's next request already sees them. The event is persisted by the Spring Modulith event
  publication registry (`event_publication` table, transactional outbox). If OpenFGA is down, the publication
  stays incomplete and a scheduled job resubmits it (`bm4a.authz.openfga.resubmit-interval`). Tuple writes are
  idempotent: "already exists" on write and "does not exist" on delete are treated as success.
- **Revocation** is effective immediately because step 1 of tenant access checks the DB membership on every
  request, independent of how fast the tuple delete reaches OpenFGA.
- **Fail closed:** if OpenFGA cannot be reached or returns an error, the check throws
  `AuthorizationUnavailableException` -> HTTP 503 `authorization_unavailable`. Protected operations never
  proceed without a positive decision. Operational policy: OpenFGA is a hard dependency for protected APIs;
  run it highly available in production.
- **Bootstrap:** with `bm4a.authz.openfga.bootstrap=true` (local and test only) the platform finds or creates
  the store named `bm4a.authz.openfga.store-name` and writes the model on startup. In production, store and model
  IDs are provisioned by deployment tooling and configured explicitly (`store-id`, `model-id`).
- Role management rules (application-level, tested): admins manage members; only owners grant or revoke
  `ADMIN`/`OWNER`; the last active owner cannot be revoked or demoted.
- Next steps: module-level (`module#viewer/editor`) and record-level relations, teams as usersets
  (`team#member`), and contextual tuples for record ownership.

### 4. PostgreSQL Row-Level Security

Not enabled in this milestone. Application-level scoping is enforced by construction and covered by isolation
tests. RLS will be added as defense in depth with `SET LOCAL app.organization_id` per transaction
(transaction-scoped so pooled connections cannot leak context), `FORCE ROW LEVEL SECURITY` on tenant tables,
and tests that interleave tenants on a single pooled connection.

## Consequences

- One extra DB lookup and one OpenFGA check per tenant-scoped request. Caching of decisions is intentionally
  not done yet (it would delay revocation); if added, keys must include user, permission, object and the
  authorization model id, with a short TTL.
- OpenFGA availability directly affects API availability (fail closed by design).
