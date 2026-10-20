# BM4A Implementation Plan - First Milestone

Goal: a secure, runnable, tested platform slice. See [REPOSITORY_ASSESSMENT.md](REPOSITORY_ASSESSMENT.md) for the
starting point and the ADRs in [docs/architecture](../architecture) for the key decisions.

## Module map

All modules live under `com.bm4a.platform.modules`. Allowed dependencies point downward only and are verified
by `ModularityTests` (Spring Modulith).

| Module | Responsibility | Depends on |
|--------|----------------|------------|
| `shared` | Error model (RFC 9457 problem details), paging, correlation IDs, JSON helpers | - |
| `identity` | JWT validation (issuer, audience, signature, expiry), security filter chain, claim extraction | shared |
| `users` | Internal user identity `(issuer, subject) -> user id`, profile, preferences, `CurrentUser` | shared, identity |
| `authorization` | `AuthorizationService` port, OpenFGA adapter, model bootstrap, tuple synchronization | shared |
| `tenancy` | Organizations, settings, memberships, `TenantContext` resolution; publishes `TenancyEvent`s | shared, users, authorization |
| `audit` | Append-only audit events, redaction, audit query API; records tenancy events in the same transaction | shared, users, authorization, tenancy |
| `metadata` | Module, field and layout definitions, validation, versioning | shared, users, tenancy, authorization, audit |
| `records` | Generic JSONB record engine | shared, users, tenancy, authorization, audit, metadata |
| `relationships` | Relationship definitions and record links | shared, users, tenancy, authorization, audit, metadata, records |
| `ratelimit` | Redis-backed rate limiting of mutating API calls | shared |

The `users` bounded context is separated from `identity`: `identity` only knows tokens, `users` owns the
application's user rows. Keycloak remains the only credential store.

## API surface (all under `/api/v1`, JSON, problem details on error)

| Area | Endpoints |
|------|-----------|
| Me | `GET /me`, `PATCH /me/profile` |
| Organizations | `POST /orgs`, `GET /orgs`, `GET /orgs/{orgId}`, `PATCH /orgs/{orgId}`, `DELETE /orgs/{orgId}` (archive) |
| Members | `GET/POST /orgs/{orgId}/members`, `PATCH/DELETE /orgs/{orgId}/members/{userId}` |
| Platform admin | `POST /platform/orgs/{orgId}/suspend`, `POST /platform/orgs/{orgId}/reactivate`, `/platform/modules/**` (global modules) |
| Modules | `GET/POST /orgs/{orgId}/modules`, `GET/PATCH/DELETE /orgs/{orgId}/modules/{moduleKey}`, `PUT .../layout`, `GET .../versions` |
| Fields | `GET/POST /orgs/{orgId}/modules/{moduleKey}/fields`, `GET/PATCH /.../fields/{fieldKey}`, `POST .../archive`, `POST .../restore` |
| Records | `GET/POST /orgs/{orgId}/modules/{moduleKey}/records`, `GET/PUT/PATCH/DELETE .../records/{id}`, `POST .../{id}/restore`, `GET .../{id}/versions[/{v}]`, `POST .../{id}/versions/{v}/restore` |
| Relationships | `GET/POST /orgs/{orgId}/relationships`, `GET/DELETE /orgs/{orgId}/relationships/{key}`, `POST /.../{key}/links`, `DELETE /.../{key}/links/{linkId}`, `GET /orgs/{orgId}/modules/{moduleKey}/records/{id}/related/{key}` |
| Audit | `GET /orgs/{orgId}/audit-events` |

## Phases and order

The roadmap order is kept, with one change: audit is implemented right after authorization (before
metadata) because every later mutation must emit audit events, and adding them retroactively is error-prone.

| # | Phase | Outcome |
|---|-------|---------|
| 0 | Baseline | Hygiene, docs, profiles, Testcontainers, compose fixes, CI, error model, correlation IDs |
| 1-2 | Identity, tenancy, authorization | Hardened JWT, internal users, organizations, memberships, OpenFGA checks, isolation tests |
| 6a | Audit | Append-only audit table and API, wired into every mutation from here on |
| 3 | Metadata | Modules, fields (17 types), layouts, versions, compatibility rules |
| 4 | Records | CRUD, validation, concurrency, filtering, history, soft delete/restore |
| 5 | Relationships | Lookup fields, relationship definitions, links, delete behavior |
| 6b | Operations | Structured logging, metrics, rate limiting |
| 7 | Admin experience | API-first: OpenAPI/Swagger UI, `.http` examples, local seed, smoke script |
| 8 | Workflow, automation, forms, reporting | Deferred; extension points documented |

## Workstreams

| Agent | Scope | Owned paths |
|-------|-------|-------------|
| Lead | Integration, shared contracts, build, security config, commits | `pom.xml`, `application*.yaml`, `modules/shared`, `modules/identity`, Flyway numbering |
| A - architect | Assessment, plan, ADRs | `docs/**` |
| B - tenancy | Organizations, memberships, users | `modules/tenancy`, `modules/users` |
| C - metadata/records | Metadata, records, relationships | `modules/metadata`, `modules/records`, `modules/relationships` |
| D - security | OpenFGA adapter, model | `modules/authorization`, `platform/src/main/resources/openfga` |
| E - infrastructure | Compose, Dockerfile, realm, CI | `infrastructure/**`, `platform/Dockerfile`, `.github/**` |
| F - reviewer | Cross-module tests, security review | `src/test/**` (integration packages) |

Shared contracts (`TenantContext`, `CurrentUser`, `AuthorizationService`, `Permission`, `AuditLog`, error
types) are written by the lead before parallel work starts. Subagents write files only; the lead reviews,
builds, and commits.

## Flyway version allocation

| Version | Content |
|---------|---------|
| V1 | Spring Modulith event publication (existing) |
| V2 | Users (`platform_user`, `user_profile`) |
| V3 | Tenancy (`organization`, `membership`) |
| V4 | Audit (`audit_event`, append-only trigger) |
| V5 | Metadata (`module_definition`, `field_definition`, `module_definition_version`) |
| V6 | Records (`record`, `record_version`, `record_unique_value`) |
| V7 | Relationships (`relationship_definition`, `record_link`) |

## Verification per increment

`./mvnw -B verify` runs unit tests and Testcontainers integration tests (PostgreSQL 18, OpenFGA). Every
commit must leave the build green, or the failure is recorded in the implementation report.
