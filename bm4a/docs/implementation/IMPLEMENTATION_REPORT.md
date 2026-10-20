# BM4A Implementation Report - First Milestone

Date: 2026-10-20. Branch: `feature/platform-foundation` (20 commits on top of `main` at `27218b3`; not pushed).

## 1. Outcome

The first milestone (a secure, runnable, tested platform slice) is complete. A tenant can define a module and its
fields through the API and use the single generic record API to create, read, update, list, filter, soft-delete,
restore and revert records. There is no Java class or table per module. Identity, tenant isolation, OpenFGA
authorization, audit, relationships, rate limiting, Docker and CI are in place.

Verification evidence:

- `./mvnw -B clean verify`: **79 tests, 0 failures, 0 errors, 0 skipped**. These run against real PostgreSQL 18,
  OpenFGA v1.22.0 and Redis 8.2 containers.
- `scripts/smoke-test.sh`: **28/28 checks passed** end to end with real Keycloak tokens and OpenFGA. It passed twice:
  once against the jar run locally (rate limiting active against real Redis), and once against the Docker image
  running inside the compose network. The second run exercises the issuer-versus-JWKS split used in containers.
- `docker build platform`: succeeds; the image runs as uid 10001.

## 2. Definition-of-done checklist

| # | Criterion | Status | Evidence |
|---|-----------|--------|----------|
| 1 | Repository inspected; plan reflects the real structure | Done | [REPOSITORY_ASSESSMENT.md](REPOSITORY_ASSESSMENT.md), [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) |
| 2 | Builds and starts with documented instructions | Done | README quick start; smoke runs |
| 3 | Schema created from migrations | Done | Flyway V1-V7; Hibernate `validate` only |
| 4 | Authentication through Keycloak; no silent dev bypass | Done | One validator chain (issuer, audience, expiry, signature); tests sign tokens instead of bypassing |
| 5 | Membership and tenant context enforced server-side | Done | `TenantAccess.require`; `TenancyIntegrationTest` |
| 6 | Tenant defines module and fields via API | Done | `MetadataIntegrationTest`, smoke test |
| 7 | Generic record API: create, read, update, list, soft-delete, restore | Done | `RecordIntegrationTest` (13 tests) |
| 8 | Payload validation follows field definitions | Done | `FieldValuesTest`, field-level errors |
| 9 | Cross-tenant access tests demonstrate denial | Done | Organizations, members, modules, records, history, lookups, links, audit, list totals |
| 10 | Authorization protects sensitive operations | Done | OpenFGA allow and deny matrix; fail-closed test |
| 11 | Audit events for important mutations | Done | `AuditIntegrationTest`; smoke test checks 8 actions |
| 12 | Docker, local setup and CI documented and verified | Done, except CI | Compose and image verified locally; CI workflow not yet run on GitHub (branch not pushed) |
| 13 | Tests pass | Done | 79/79 |
| 14 | No secrets, destructive changes, fake implementations or unreviewed rewrites | Done | See section 7 |

## 3. What was implemented

| Module | Highlights |
|--------|-----------|
| `shared` | RFC 9457 problem details (stable `code`, field `errors`, `correlationId`); correlation-ID filter; paging; ETag/If-Match helpers |
| `identity` | Own `JwtDecoder` (RS256 via JWKS), with the issuer and JWKS URI configured separately; audience `bm4a-api`; realm roles mapped only for platform admin; problem-details 401/403 |
| `users` | `(issuer, subject)` mapped to an internal UUID with race-free just-in-time provisioning; suspended users blocked; `/me`, profile and preferences |
| `authorization` | `AuthorizationService` port; OpenFGA SDK confined to an `internal` package; model in DSL and JSON; local/test bootstrap; tuples applied after commit, recorded in the Modulith outbox and resubmitted on failure; fail closed (503) |
| `tenancy` | Organizations (settings, If-Match, archive), platform suspend/reactivate, memberships with owner/admin rules and last-owner protection under a row lock; `TenantContext` passed explicitly (no ThreadLocal) |
| `audit` | Append-only `audit_event`: triggers block UPDATE, DELETE and TRUNCATE. Redaction, 32 KiB cap, correlation IDs, tenant-scoped query API |
| `metadata` | Tenant and platform-global modules; 17 field types; per-type config allow-list; layouts; definition versions with snapshots; evolution rules (immutable keys, compatible type changes, options never removed, fields never deleted) |
| `records` | JSONB canonical values; create, PUT (replace), PATCH (merge patch); If-Match; history and revert; soft delete and restore; unique fields through a side table; lookup integrity with RESTRICT; allow-listed filters, sorting and search; 256 KiB cap; `RecordEvent` |
| `relationships` | 1:1, 1:N and N:M links with partial unique indexes; RESTRICT or DETACH via `RecordDeletionGuard`; related-records queries |
| `ratelimit` | Redis fixed window per user for POST, PUT, PATCH and DELETE (atomic Lua); 429 with `Retry-After`; fails open with metrics ([RATE_LIMITING.md](../operations/RATE_LIMITING.md)) |
| Infrastructure | Single compose project with health checks, pinned images, localhost-only ports, env-driven Postgres init, OpenFGA pre-shared key, dev realm without client secrets, multi-stage non-root Dockerfile, GitHub Actions CI |
| Docs | ADR-001, ADR-002, API guide, `.http` collection, example module, README, roadmap status |

## 4. Key architecture decisions

- **Storage:** relational metadata plus JSONB record values, with targeted side tables for uniqueness, links and
  history ([ADR-001](../architecture/ADR-001-dynamic-record-storage.md)). No runtime DDL and no general EAV.
- **Tenant context:** the organization in the path is only a claim. It's verified against the membership table on
  every request, so revocation takes effect immediately. Foreign or unknown IDs return 404.
- **Authorization:** OpenFGA holds tuples derived from DB memberships, applied after commit through an outbox; any
  error fails closed ([ADR-002](../architecture/ADR-002-tenancy-and-authorization.md)).
- **Persistence:** `JdbcClient` with explicit SQL. Every statement carries the `organization_id` predicate, and
  dynamic queries are assembled only from constant fragments with bound keys and values.
- **Module boundaries:** each module's API lives in its root package; `internal` and `web` stay private. Spring
  Modulith `verify()` runs in CI.

## 5. Workstreams (multi-agent)

| Agent | Work | Integration |
|-------|------|-------------|
| Lead | Design, shared contracts, identity, users, authorization, tenancy, audit, metadata, records, relationships, docs, all commits | - |
| [Agent E - infrastructure](cae411a9-2f24-4a2d-987f-4ed9d8606de3) | Compose, health checks, realm, Postgres init, Dockerfile, CI (live stack verified) | Reviewed, committed as `e1d4304` and `38c7713` |
| [Agent ops - rate limiting](e8fb1b89-2334-433e-83f3-36a31ba4aa05) | `ratelimit` module in an isolated git worktree (branch `feat/ratelimit`) | Reviewed, cherry-picked as `2df1a7f`; log-prefix follow-up `f4109c8` |
| [Agent F - reviewer](4588db49-f231-4b06-b450-b17d3340bbeb) | Read-only security review; stopped before reporting | The lead did the review instead (section 7) |

The audit API skeleton (`AuditEntry`, `AuditLog`, `AuditQuery`, `Redaction`, migration) appeared in the workspace
from an unidentified source. With the user's approval it was adopted, its migration renumbered from V3 to V4, and
the implementation completed.

## 6. Commands run (summary)

| Command | Result |
|---------|--------|
| `./mvnw -B test` (baseline, before changes) | Compiled; `contextLoads` failed on unresolved placeholder `KEYCLOAK_HOST` (baseline failure) |
| `./mvnw -B test` after each increment | Green before every feature commit |
| `./mvnw -B clean verify` (final) | 79 tests, BUILD SUCCESS |
| `docker compose -f infrastructure/docker-compose.yml --env-file infrastructure/.env.example -p bm4a-smoke up -d` | All services healthy |
| `API_URL=http://localhost:8081 scripts/smoke-test.sh` (jar) / `:8082` (container) | PASS: 28 checks (both) |
| `scripts/seed-demo.sh`, Swagger UI, `/v3/api-docs` | Working (35 documented paths) |
| `docker build -t bm4a-platform:local platform` | Success, non-root |
| `docker compose ... -p bm4a-smoke down -v` | Smoke environment removed (user volumes untouched) |

Tests skipped: none. Tests weakened or removed: none. The original `contextLoads` test was kept and now runs against
Testcontainers.

## 7. Security review, limitations and risks

The lead reviewed the following (Agent F was stopped before reporting):

- **Secrets:** no secret files are tracked. The only credential-like values are the development placeholders in the
  `.env.example` files and the realm, and they are labelled as such.
- **Access checks:** every tenant-scoped endpoint calls `TenantAccess.require` with the intended permission, and
  every platform endpoint checks the realm role.
- **Tenant scoping and injection:** repository SQL was reviewed during implementation for tenant predicates and
  constant-only SQL assembly; the isolation and injection tests cover it.

Found and fixed during the work:

- Typo'd and mutable principal claim, plus the missing audience check (original `JwtAuthConverter`).
- Committed `.env` files and the realm client secret.
- Spring Modulith observability proxying a servlet filter and the exception handler.
- Unbounded record size.

Remaining limitations and risks:

1. **CI not executed yet.** The workflow runs on the first push; the same commands pass locally.
2. **No PostgreSQL row-level security yet.** Isolation is enforced in the application and tested. RLS is the planned
   defense in depth (ADR-002).
3. **Audit growth from denials.** Each denied request by a member writes an audit row. Read requests aren't rate
   limited, so a member can grow the audit table. Consider sampling or throttling denial audits.
4. **Rate limiting fails open** when Redis is down (by design, with metrics). It covers writes only, with no per-IP
   limit for unauthenticated traffic; put a gateway or WAF in front in production.
5. **OpenFGA is a hard dependency** for protected endpoints (fail closed, 503). Run it highly available. Decisions
   are not cached.
6. **JSONB range filters and typed sorts** are not index-backed. Fine for moderate module sizes; add measured
   expression indexes or projections later (ADR-001).
7. **Restoring a record doesn't restore** links removed by DETACH, and doesn't re-validate lookup targets.
8. **Members are added by internal user ID.** There's no e-mail invitation flow yet, deliberately, to avoid user
   enumeration.
9. **Dev realm uses the password grant** on the public `bm4a-cli` client. That's for local development only; disable
   it in any shared environment.
10. **File/image fields store references only.** No file storage service exists yet.
11. **API docs are public in non-prod profiles.** In `prod` they are off unless `API_DOCS_ENABLED=true`.

## 8. How to start from a clean checkout

```bash
cp infrastructure/.env.example infrastructure/.env
cp platform/.env.example platform/.env
docker compose -f infrastructure/docker-compose.yml up -d          # wait for "healthy"
cd platform && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
# in another shell, from the repo root:
scripts/smoke-test.sh && scripts/seed-demo.sh
```

Existing local volume from the old setup: run `docker compose -f infrastructure/docker-compose.yml down -v` once,
because the Postgres init script only runs on an empty volume.

## 9. Next recommended milestone

1. Push the branch, run CI, open a PR.
2. **Authorization depth:** module-level and record-level relations in OpenFGA (`module#viewer/editor`, record
   owner), teams as usersets, and invitations by e-mail with tokens.
3. **Defense in depth:** PostgreSQL RLS with transaction-scoped `SET LOCAL`, plus pool-reuse tests; audit throttling
   for denials.
4. **Minimal admin UI:** a metadata-driven forms and lists client on top of the existing API (framework to be chosen).
5. **Workflow engine v1** (versioned state machines on records, consuming `RecordEvent`). Then the automation engine
   with idempotent, allow-listed actions.
