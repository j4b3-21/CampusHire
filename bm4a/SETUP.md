# BM4A Setup and Usage

- [Part 1 - Minimal setup](#part-1---minimal-setup): running locally in about 5 minutes.
- [Part 2 - Extensive setup](#part-2---extensive-setup): every step explained, plus verification, daily use, tests
  and production notes.

---

## Part 1 - Minimal setup

**You need:** Docker (running), JDK 25, `curl`, `jq`.

```bash
# 1. Configuration files (development defaults)
cp infrastructure/.env.example infrastructure/.env
cp platform/.env.example platform/.env

# 2. Start PostgreSQL, Keycloak, OpenFGA and Redis
docker compose -f infrastructure/docker-compose.yml up -d

# 3. Start the platform (leave this running; wait for "Started PlatformApplication")
cd platform && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# 4. In a second terminal, from the repository root: verify and load demo data
scripts/smoke-test.sh        # expect "PASS: 28 checks"
scripts/seed-demo.sh         # creates a demo organization with example records
```

**Use it:**

```bash
TOKEN=$(scripts/dev-token.sh alice)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/me
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/orgs
```

Browse the API at http://localhost:8080/swagger-ui.html.

**Stop:** press `Ctrl+C` in the platform terminal, then run `docker compose -f infrastructure/docker-compose.yml down`.

If a step fails, check [Troubleshooting](#11-troubleshooting).

---

## Part 2 - Extensive setup

### 1. Prerequisites

| Tool | Version | Check | Needed for |
|------|---------|-------|------------|
| Docker Desktop / Engine with Compose v2 | 24+ | `docker info`, `docker compose version` | Infrastructure, tests (Testcontainers), image build |
| JDK | 25 | `java -version` | Running from source, tests (not needed for the all-Docker option) |
| curl, jq | any | `curl --version`, `jq --version` | Helper scripts |
| Git | any | `git --version` | Source |

The Maven wrapper (`platform/mvnw`) downloads Maven, so you don't need to install it.

Free local ports (bound to `127.0.0.1` only):

| Port | Service |
|------|---------|
| 2345 | PostgreSQL |
| 2346 | Keycloak |
| 2347 | OpenFGA HTTP API |
| 2349 | Redis |
| 8080 | Platform API |

Check with `lsof -i :8080` (macOS/Linux). If a port is taken, see [Troubleshooting](#11-troubleshooting).

### 2. Get the code

```bash
git clone https://github.com/j4b3-21/BM4A.git
cd BM4A
git checkout feature/platform-foundation   # until it is merged into main
```

### 3. Configure environment files

There are two untracked `.env` files, each created from a committed template:

```bash
cp infrastructure/.env.example infrastructure/.env
cp platform/.env.example platform/.env
```

Fresh copies work as-is for local development. If you change a password or key, change it in **both** files where it
appears:

| Value | `infrastructure/.env` | `platform/.env` |
|-------|-----------------------|-----------------|
| Platform DB password | `PLATFORM_DB_PASSWORD` | `PLATFORM_DB_PASSWORD` |
| OpenFGA key | `OPENFGA_PRESHARED_KEY` | `OPENFGA_PRESHARED_KEY` |

Other values in `infrastructure/.env`:

- `POSTGRES_USER` / `POSTGRES_PASSWORD`: database superuser (container only).
- `KEYCLOAK_DB*`, `OPENFGA_DB*`, `PLATFORM_DB*`: one database and login per service, created on first start.
- `KEYCLOAK_ADMIN_USER` / `KEYCLOAK_ADMIN_PASSWORD`: Keycloak admin console login.
- `KC_HOSTNAME=http://localhost:2346`: keeps the token issuer stable. Don't change it unless you also change
  `KEYCLOAK_ISSUER_URI`.

Other values in `platform/.env` (read only by the `local` profile):

- `PLATFORM_DB_HOST`/`PORT`/`DB`/`USER`: database connection.
- `KEYCLOAK_HOST`, `KEYCLOAK_PORT`, `KEYCLOAK_REALM`, `KEYCLOAK_AUDIENCE`: token validation (realm `bm4a`, audience
  `bm4a-api`).
- `OPENFGA_API_URL`, `OPENFGA_STORE_NAME`: authorization server.
- `REDIS_HOST`, `REDIS_PORT`: rate limiting.

Never commit the `.env` files; they are gitignored.

> **Upgrading from an older checkout?** The old `.env` files are incompatible (for example, an old `platform/.env`
> may point at realm `master`), so re-copy both templates. The old Postgres volume was also initialized with
> different users, so reset it once: `docker compose -f infrastructure/docker-compose.yml down -v`. This deletes
> local data. You can also remove the old network with `docker network rm bm4a-network`.

### 4. Start the infrastructure

```bash
docker compose -f infrastructure/docker-compose.yml up -d
docker compose -f infrastructure/docker-compose.yml ps
```

Wait until `postgres`, `keycloak`, `openfga` and `redis` all show `healthy`. Keycloak takes about 20-60 seconds the
first time; `openfga-migrate` runs once and exits.

What happens on first start:

- PostgreSQL runs `infrastructure/postgres/init/01-init.sh`, which creates the `keycloak`, `openfga` and `platform`
  databases and users. This runs only on an empty volume.
- Keycloak imports the development realm `bm4a` from `infrastructure/keycloak/realms/bm4a-realm.json`.
- OpenFGA migrates its schema and requires the pre-shared key on every API call.

Optional checks:

```bash
# Keycloak issues tokens for the dev users
scripts/dev-token.sh alice | cut -c1-40

# OpenFGA rejects calls without the key (401) and accepts them with it (200)
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:2347/stores
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer change-me-openfga-key" http://localhost:2347/stores
```

The Keycloak admin console is at http://localhost:2346, using `KEYCLOAK_ADMIN_USER` / `KEYCLOAK_ADMIN_PASSWORD`.

### 5. Run the platform

Pick one of three options.

**Option A - from source with Maven (recommended for development)**

```bash
cd platform
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

**Option B - from your IDE**

- Run `com.bm4a.platform.PlatformApplication` with active profile `local`.
- Set the working directory to `platform/`, so `platform/.env` is found.

**Option C - everything in Docker**

```bash
docker compose -f infrastructure/docker-compose.yml --profile app up -d --build
```

This builds the image from `platform/Dockerfile` (multi-stage; the app runs as non-root uid 10001) and wires it to
the other containers.

What happens at startup:

1. Flyway applies migrations V1-V7, creating the whole schema.
2. With the `local` profile, the OpenFGA store `bm4a` is found or created, and the authorization model in
   `platform/src/main/resources/openfga/model.json` is written.
3. The API listens on port 8080.

To use another port:

- Option A: `SERVER_PORT=8081 ./mvnw spring-boot:run -Dspring-boot.run.profiles=local`.
- Option C: edit the `ports` entry in `infrastructure/platform/docker-compose.yml`.

### 6. Verify the installation

```bash
curl -s http://localhost:8080/actuator/health/readiness     # {"status":"UP",...}
scripts/smoke-test.sh                                       # 28 end-to-end checks
```

On another port, prefix the script with the URL: `API_URL=http://localhost:8081 scripts/smoke-test.sh`. The smoke
test uses real Keycloak tokens and OpenFGA and covers:

- authentication rejection
- tenant isolation
- module definition and record lifecycle
- concurrency control
- membership and revocation
- audit events

### 7. Using BM4A

#### 7.1 Development users

These users and passwords are for **local development only**:

| User | Password | Notes |
|------|----------|-------|
| `alice` | `alice-dev-password` | Regular user |
| `bob` | `bob-dev-password` | Regular user (use for isolation tests) |
| `platform-admin` | `admin-dev-password` | Realm role `bm4a-platform-admin`: global modules, organization suspension |

Get a token, valid for 5 minutes:

```bash
TOKEN=$(scripts/dev-token.sh alice)
```

#### 7.2 Typical workflow

```bash
API=http://localhost:8080/api/v1
H=(-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json")

# Create an organization (you become OWNER) and remember its ID
ORG=$(curl -s "${H[@]}" -X POST $API/orgs -d '{"name":"My Company"}' | jq -r .id)

# Define a module - pure configuration, no code (example with every field type in docs/examples/)
curl -s "${H[@]}" -X POST $API/orgs/$ORG/modules --data @docs/examples/example-asset-module.json | jq '.apiKey, .version'

# Create, read, update, list records
ID=$(curl -s "${H[@]}" -X POST $API/orgs/$ORG/modules/example_asset/records \
     -d '{"data":{"name":"Laptop","serial":"LAP-1","quantity":2}}' | jq -r .id)
curl -s "${H[@]}" $API/orgs/$ORG/modules/example_asset/records/$ID | jq .data
curl -s "${H[@]}" -X PATCH -H 'If-Match: "1"' $API/orgs/$ORG/modules/example_asset/records/$ID \
     -d '{"data":{"quantity":5}}' | jq .version
curl -s "${H[@]}" "$API/orgs/$ORG/modules/example_asset/records?filter%5Bquantity%5D%5Bgte%5D=3&sort=-quantity" | jq .totalElements

# Invite a teammate: they call /me once, you add their ID
BOB_ID=$(curl -s -H "Authorization: Bearer $(scripts/dev-token.sh bob)" $API/me | jq -r .id)
curl -s "${H[@]}" -X POST $API/orgs/$ORG/members -d "{\"userId\":\"$BOB_ID\",\"role\":\"MEMBER\"}"

# See what happened
curl -s "${H[@]}" "$API/orgs/$ORG/audit-events?size=20" | jq '.items[].action'
```

Rules to remember:

- **If-Match:** every update or delete needs `If-Match` with the current version from the `ETag` header or the
  `version` field. Missing gives `428`; stale gives `412`.
- **Errors:** they come back as JSON with a stable `code`, plus field-level `errors` for validation.
- **Roles:**
  - Members can read and write records and read definitions.
  - Admins can also change modules, fields, relationships and members, and read the audit log.
  - Owners can also archive the organization and manage admins and owners.

The full reference (endpoints, permissions, filter operators, error codes) is in [docs/api/README.md](docs/api/README.md).
Ready-made requests are in [docs/api/bm4a.http](docs/api/bm4a.http), and interactive docs are at
http://localhost:8080/swagger-ui.html.

#### 7.3 Demo data

```bash
scripts/seed-demo.sh            # as alice; pass "bob" to seed as bob
```

### 8. Running the tests

```bash
cd platform
./mvnw -B verify                # needs Docker running (Testcontainers)
```

- The tests start their own PostgreSQL, OpenFGA and Redis containers, so the compose stack isn't needed.
- 79 tests: unit tests, plus integration tests for authentication, tenancy isolation, authorization, audit,
  metadata, records, relationships, rate limiting and module boundaries.
- Reports are in `platform/target/surefire-reports/`.
- CI (`.github/workflows/ci.yml`) runs the same build on every push and pull request. It also builds the Docker
  image and validates the compose files.

### 9. Stopping and resetting

| Goal | Command |
|------|---------|
| Stop the platform | `Ctrl+C` (Options A/B) or `docker compose -f infrastructure/docker-compose.yml --profile app stop platform` |
| Stop everything, keep data | `docker compose -f infrastructure/docker-compose.yml --profile app down` |
| Wipe all local data (DB, realm changes, OpenFGA tuples, Redis) | `docker compose -f infrastructure/docker-compose.yml --profile app down -v` |

### 10. Preparing a non-local environment (production notes)

Run with `SPRING_PROFILES_ACTIVE=prod`. The prod profile has no defaults, so every value below must be set:

| Variable | Example |
|----------|---------|
| `PLATFORM_DB_URL`, `PLATFORM_DB_USER`, `PLATFORM_DB_PASSWORD` | `jdbc:postgresql://db.internal:5432/platform` |
| `KEYCLOAK_ISSUER_URI` | `https://login.example.com/realms/bm4a` (must equal the token `iss`) |
| `KEYCLOAK_JWK_SET_URI` | `https://login.example.com/realms/bm4a/protocol/openid-connect/certs` |
| `KEYCLOAK_AUDIENCE` | `bm4a-api` |
| `OPENFGA_API_URL`, `OPENFGA_PRESHARED_KEY` | Internal OpenFGA URL and a strong random key |
| `OPENFGA_STORE_ID`, `OPENFGA_MODEL_ID` | Provisioned once (see below) |
| `REDIS_HOST`, `REDIS_PORT` | Rate limiting store (`RATE_LIMIT_WRITES_PER_MINUTE`, default 300) |
| `API_DOCS_ENABLED` | `false` (default) unless the API docs should be public |

Checklist:

1. **OpenFGA store and model:** create them once with deployment tooling, then set the two IDs. Bootstrap is
   disabled in prod, and startup fails without them. For example, with the
   [OpenFGA CLI](https://openfga.dev/docs/getting-started/cli):
   `fga store create --name bm4a --model platform/src/main/resources/openfga/model.fga`.
   When the model changes, write a new model version and update `OPENFGA_MODEL_ID`.
2. **Keycloak realm:** don't import the development realm as-is.
   - Remove the dev users.
   - Disable the password grant (`directAccessGrantsEnabled`) on `bm4a-cli`, or replace it with a proper frontend
     client (authorization code + PKCE).
   - Keep the audience mapper so tokens carry `aud: bm4a-api`.
   - Configure MFA, social login and SSO in Keycloak as needed.
   - Grant `bm4a-platform-admin` only to operators.
3. **Network:** don't expose PostgreSQL, Redis, OpenFGA or the Keycloak admin console publicly. Terminate TLS in
   front of the platform and Keycloak.
4. **Secrets:** inject them from a secret manager. Never use the `change-me` values.
5. **Probes and metrics:**
   - Liveness: `/actuator/health/liveness`.
   - Readiness: `/actuator/health/readiness`.
   - Metrics: `/actuator/metrics`.
   - Logs are ECS JSON in `prod`.
6. **Availability:** OpenFGA is required for every protected request (fail closed: `503`), so run it highly
   available. Redis is optional; if it's down, rate limiting is skipped and a metric is recorded.

### 11. Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| Keycloak or OpenFGA keep restarting with database auth errors | The Postgres volume predates the current init script. Run `docker compose -f infrastructure/docker-compose.yml down -v` (deletes local data), then `up -d`. |
| `Bind for 127.0.0.1:8080 failed: port is already allocated` | Another app uses the port. Stop it, use `SERVER_PORT=8081` (Option A), or edit `infrastructure/platform/docker-compose.yml` (Option C). |
| Every API call returns `401` | Expired token (5 minutes), or the wrong issuer or audience. Use `scripts/dev-token.sh`; the issuer must be exactly `http://localhost:2346/realms/bm4a`. |
| `403 access_denied` | Your role lacks the permission (for example, a member changing modules). |
| `404` for an organization that exists | You are not an active member (non-members get `404` by design). |
| `503 authorization_unavailable` | OpenFGA is down, or `OPENFGA_PRESHARED_KEY` differs between the two `.env` files. |
| Startup error: `store-id and model-id are required` | You're not running the `local` profile; add `-Dspring-boot.run.profiles=local` or set the IDs. |
| Startup error: cannot connect to `localhost:2345` | The infrastructure isn't running or healthy; check `docker compose ... ps`. |
| `428 precondition_required` / `412 version_conflict` | Send `If-Match: "<current version>"`; re-read the resource when you get `412`. |
| `429 rate_limited` | More than 300 writes per minute. Wait for `Retry-After`, or raise `RATE_LIMIT_WRITES_PER_MINUTE` locally. |
| Tests fail: "Could not find a valid Docker environment" | Start Docker; integration tests use Testcontainers. |
| `scripts/*.sh: Permission denied` | `chmod +x scripts/*.sh` |
