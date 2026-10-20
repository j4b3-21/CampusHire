# BM4A Repository Assessment

Assessment date: 2026-10-20. Baseline commit: `27218b3` (`main`, "Merge branch 'feature/User' into main").

## 1. Repository layout (as found)

```
BM4A/
├── README.md                     # one line: "# BM4A"
├── BM4A_Roadmap.md               # product roadmap (phases 0-8)
├── .idea/                        # IDE files (partly tracked)
├── infrastructure/
│   ├── .env                      # tracked, contains development credentials
│   ├── docker-compose.yml        # `include:` of the files below
│   ├── postgres/                 # postgres:18, init SQL with hardcoded passwords
│   ├── keycloak/                 # keycloak 26.3 start-dev + realm import
│   │   └── realms/bm4a-realm.json  # realm "bm4a", one confidential client with committed secret
│   ├── openfga/                  # openfga:latest + migrate job
│   └── redis/                    # redis:8 with AOF volume
└── platform/                     # Spring Boot application
    ├── pom.xml, mvnw, .mvn/
    ├── Dockerfile                # single stage, copies a jar name that is never built
    ├── docker-compose.yaml       # app service, env_file ../.env (missing), external network
    ├── .env                      # tracked, contains development credentials
    └── src/
        ├── main/java/com/bm4a/platform/
        │   ├── PlatformApplication.java
        │   ├── health/controller/{HealthController,DemoController}.java
        │   └── modules/auth/config/{SecurityConfig,JwtAuthConverter}.java
        ├── main/resources/application.yaml
        ├── main/resources/db/migration/V1__spring_modulith.sql
        └── test/java/com/bm4a/platform/PlatformApplicationTests.java
```

No frontend exists. No CI configuration exists. No documentation beyond the roadmap exists.

## 2. Stack and versions

| Item | Found |
|------|-------|
| Build | Maven (wrapper), single module `com.bm4a:platform` |
| Java | 25 (`java.version`), local JDK 25.0.4 |
| Spring Boot | 4.1.0 (Spring Framework 7, Spring Security 7, Jackson 3, Hibernate 7.4) |
| Spring Modulith | 2.1.0 (core, JPA event registry, actuator, observability) |
| Persistence | Spring Data JPA (`ddl-auto: validate`), Flyway 12 + `flyway-database-postgresql`, PostgreSQL driver 42.7 |
| Security | OAuth2 resource server (JWT), OAuth2 client (unused by any code path) |
| Other | Redis starter (unused), Actuator, Validation |
| Test | `spring-boot-starter-*-test`, `spring-modulith-starter-test` (no Testcontainers) |

These versions are kept. They are current, mutually compatible, and nothing justifies changing them.

## 3. Baseline build and test (before any change)

Command: `cd platform && ./mvnw -B test`

Result: **compilation succeeds, the single test fails.**

```
PlatformApplicationTests.contextLoads » IllegalState Failed to load ApplicationContext
Caused by: PlaceholderResolutionException: Could not resolve placeholder 'KEYCLOAK_HOST'
Tests run: 1, Failures: 0, Errors: 1, Skipped: 0 -- BUILD FAILURE
```

Cause: `application.yaml` requires environment variables (`KEYCLOAK_HOST`, `PLATFORM_DB`, ...) and a live
database. No test profile or test infrastructure exists. This is a baseline failure, not a regression.

## 4. What works today

- The application compiles and, with every variable exported and the infrastructure running, starts as an
  OAuth2 resource server that accepts Keycloak JWTs.
- Flyway is wired and creates the Spring Modulith `event_publication` table.
- The infrastructure compose files start PostgreSQL, Keycloak (with a realm import), OpenFGA and Redis.

## 5. Defects and risks found

### Security

1. `platform/.env`, `infrastructure/.env` and the realm client secret (`bm4a-platform-secret`) are committed.
   Values are development-only, but the pattern leaks secrets as soon as real values are used.
2. JWT validation does not check the audience. Any token from the realm (for any client) is accepted.
3. `JwtAuthConverter` uses `preffered_username` (typo) as principal name, so the principal name is `null`.
   Even when spelled correctly, a username is mutable; the stable identity is `(iss, sub)`.
4. `jwt.auth.converter.resource-id: public` does not match any client, so client roles never map.
5. `DemoController` relies on Keycloak client roles for authorization. Authorization should come from
   OpenFGA (per the roadmap), with tenant membership checked server-side.
6. Postgres init SQL hardcodes service passwords. OpenFGA has no API authentication (preshared key).
7. `platform/.env` sets `KEYCLOAK_REALM=master` while the imported realm is `bm4a`.

### Build and runtime

8. `contextLoads` cannot pass without external services. No test profile, no Testcontainers.
9. OAuth2 client registration requires `KEYCLOAK_CLIENT_SECRET` at startup although no login flow exists.
10. `/health` duplicates Actuator health; there are no liveness/readiness groups.
11. The Redis starter is present but unused; Redis health would make the app "DOWN" if Redis is absent.

### Infrastructure

12. `platform/Dockerfile` copies `target/bm4a-platform.jar`, but the build produces
    `platform-0.0.1-SNAPSHOT.jar`. Single stage, runs as root.
13. `platform/docker-compose.yaml` reads `../.env` (does not exist) and every compose file uses an
    `external: true` network that must be created manually.
14. `openfga/openfga:latest` is unpinned. Keycloak, OpenFGA and Redis have no health checks.
15. Keycloak's issuer differs between host access (`localhost:2346`) and container access
    (`bm4a-keycloak:8080`), so a containerized platform would reject host-issued tokens.

### Repository hygiene

16. The index contained a leftover unmerged entry for `platform/.DS_Store`; `.DS_Store` and IDE files were
    tracked; there was no root `.gitignore`.

## 6. Gaps against the roadmap

Everything beyond authentication plumbing is missing: tenancy, organizations, memberships, user profiles,
authorization (OpenFGA is deployed but not called), metadata engine, record engine, relationships, audit,
error model, API documentation, CI, and developer documentation.

## 7. Conventions adopted

- Base package `com.bm4a.platform`; bounded contexts live in `com.bm4a.platform.modules.<module>`
  (extends the existing `modules.auth` convention). Each module exposes its API in the module root package;
  implementation lives in `internal` and HTTP adapters in `web` subpackages. Spring Modulith verifies this.
- Existing `modules.auth` becomes `modules.identity` (authentication). The `health` package is replaced by
  Actuator probes.
- Flyway (already present) for every schema change; UUID identifiers; UTC `timestamptz`.
