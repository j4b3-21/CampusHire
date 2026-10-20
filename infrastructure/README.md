# CampusHire local infrastructure (development only)

Docker Compose stack (project `campushire`) for running CampusHire on a laptop. Every host port binds to
`127.0.0.1`; credentials are well-known dev placeholders. Never reuse this stack or its realm in production.

```bash
make infra-up        # creates infrastructure/.env from .env.example on first run, starts dependencies, waits for health
make backend         # API on the host (local profile; creates backend/.env from backend/.env.example)
make frontend        # Next.js dev server on the host (copy frontend/.env.example to frontend/.env.local)
make token USER=student1@iiitt.ac.in   # access token via the dev-only campushire-cli client
make up / make down  # whole stack in Docker, including backend and frontend (compose profile `app`)
make infra-reset     # deletes ALL volumes (database, Keycloak realm, MinIO objects, Redis)
```

## Services

| Service | Host port | Notes |
|---------|-----------|-------|
| PostgreSQL 18 | 5442 | DBs/roles `keycloak`, `openfga`, `campushire` created by `postgres/init/01-init.sh` on first start |
| Keycloak 26.3 | 8180 | Admin console http://localhost:8180/admin (`KEYCLOAK_ADMIN_*`); realm `campushire` imported from `keycloak/realms/` |
| OpenFGA v1.22.0 | 8280 | HTTP API, pre-shared key auth (`OPENFGA_PRESHARED_KEY`); `openfga-migrate` runs first |
| Redis 8.2 | 6389 | AOF persistence |
| MinIO | 9100 / 9101 | S3 API / console; `minio-init` creates private bucket `campushire-files` and the API key `S3_ACCESS_KEY` |
| Mailpit | 8025 / 1025 | Web UI / SMTP; catches Keycloak and API email |
| backend (profile `app`) | 8090 | `backend/Dockerfile`, build context = repo root; override with `BACKEND_HOST_PORT` |
| frontend (profile `app`) | 3000 | `frontend/Dockerfile` (Next.js standalone); override with `FRONTEND_HOST_PORT` |

MinIO no longer publishes community images, so the stack uses the Pigsty-maintained builds
(`pgsty/minio`, `pgsty/mc`), which are built from the same AGPL source.

## Dev users (realm `campushire`)

All have password `dev-password-1` and a verified email: `tpo@campushire.dev` (Priya Raman),
`coordinator.cse@campushire.dev`, `student1@iiitt.ac.in`, `student2@iiitt.ac.in`, `recruiter@acme.dev`,
`admin@campushire.dev` (realm role `campushire-platform-admin`).

Clients: `campushire-web` (public, code flow + PKCE S256, http://localhost:3000), `campushire-cli` (public, password
grant only, for scripts/smoke tests/k6), `campushire-api` (bearer-only audience). Tokens carry `aud: campushire-api`
and `iss: http://localhost:8180/realms/campushire`.

## Backend configuration in Docker

The `backend` container runs **without a Spring profile**: `application.yaml` plus explicit environment in
`backend/docker-compose.yml` (container hostnames, `OPENFGA_BOOTSTRAP=true`). The `local` profile is meant for the
host (localhost ports and `backend/.env`); `prod` requires `PLATFORM_DB_URL` and logs ECS JSON. The issuer is the
browser-facing URL (`KC_HOSTNAME`), while JWKS keys are fetched in-network from `http://keycloak:8080`.

## Google sign-in

The realm defines identity provider `google` (hosted domain `GOOGLE_HOSTED_DOMAIN`, trusted email, sync mode
IMPORT), disabled by default. Keycloak substitutes `${GOOGLE_*}` placeholders when it imports the realm, and the
import only runs when the realm does not exist yet. To enable it:

1. Create a Google OAuth client (Web application) with redirect URI
   `http://localhost:8180/realms/campushire/broker/google/endpoint`.
2. In `infrastructure/.env` set `GOOGLE_IDP_ENABLED=true`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`
   (and `GOOGLE_HOSTED_DOMAIN` if not `iiitt.ac.in`).
3. Re-import the realm: `make infra-reset && make infra-up`. Alternatively, keep your data and enter the
   values in the admin console (Identity providers, then google).

## Changing configuration

- Database variables apply only when the postgres volume is created; the realm file and `GOOGLE_*` values only when
  the realm is first imported. Both need `make infra-reset` afterwards.
- Keep `backend/.env` in sync with `infrastructure/.env` when you change passwords or keys.
