# Demo and load-test data seeder

`seed.py` (Python 3.9+, standard library only) fills a CampusHire installation with a realistic demo institute
through the same APIs the web app uses: the Keycloak admin API for accounts, the CampusHire/BM4A APIs for
everything else. `data.py` holds the vocabulary (departments, batches, names, companies, CTCs).

```bash
make seed                                                        # demo defaults on the dev stack
python3 scripts/seed/seed.py --target dev --students 120 --companies 6 --drives 4 --student-accounts 3   # quick
python3 scripts/seed/seed.py --target dev --reset                # delete demo accounts, archive the institute
make seed-load                                                   # load-test data on a staging/prod-compose stack
```

Prerequisites (dev): `make infra-up` and the API on http://localhost:8090 (`make backend`). The placement officer
`tpo@campushire.dev` and the Keycloak admin credentials come from the dev realm and `infrastructure/.env`.

## What it creates

| Step | How |
|------|-----|
| Institute `IIIT Tiruchirappalli (Demo)` | `POST /orgs` as the TPO (direct grant on `campushire-cli`), then `POST /orgs/{id}/campushire/setup` with allowed domain `iiitt.ac.in` |
| Departments CSE, ECE, EEE, MECH, CIVIL, AIDS; batches B.Tech 2023-2027 / 2024-2028 / 2025-2029, M.Tech 2025-2027 | Record API, reused by `code` / `name` |
| `--students` (2,000) students | Generated CSV imported through the data-exchange import (`POST .../modules/student/imports`, multipart `file` + `options` JSON, UPSERT on `roll_number`), polled until finished; counts and the first errors are printed |
| `--companies` (50, spread over tiers and industries) with 2 contacts, 1-2 follow-ups and 1-2 jobs (CTC 3.5-45 LPA) | Record API |
| Coordinators `coordinator.<dept>@campushire.dev` and recruiters for 5 companies (`hr.<company>@recruiters.example.com`) | Keycloak user (verified e-mail) + CampusHire invitation preset, accepted with the invitee's own token |
| `--student-accounts` (25) Keycloak users whose e-mail matches a student record (final year first, spread over departments) | Keycloak partial import (`emailVerified=true`, password `--password`), then `POST /campushire/onboarding/claim` as each student |
| `--drives` (30) drives: completed, in progress, registration closed, open, upcoming, draft, cancelled, with eligibility rules | Record API, then the lifecycle endpoints (`publish`, `close`, `start`, `complete`, `cancel`); completed drives are back-dated |
| Applications for past and running drives | CSV import into `application` (lookups by drive ID, roll number, company ID, department code), then the bulk transition endpoint (shortlisted, assessment, interview, rejected) |
| Offers | `POST /campushire/offers`, one per student (policy `maxOffersPerStudent=1`); students with accounts accept or decline |
| Applications to open drives | Students with accounts call `GET .../student/drives` and `POST .../drives/{id}/apply` for eligible drives |
| `loadtest/data/seed.json` | Org ID, account e-mails and password, drive IDs for the k6 scenarios (git-ignored, mode 600) |

Roll numbers look like `CS23B0001` (department prefix, entry year, `B`/`M` for B.Tech/M.Tech, sequence), e-mails
are `<roll lower>@iiitt.ac.in`. CGPA is normal-ish (mean 7.65, clamped to 5.5-9.8), most students have no backlogs,
and 10th/12th percentages, skills, phone, date of birth and profile links are filled in.

## Options

| Option | Default | Meaning |
|--------|---------|---------|
| `--target dev\|prod` | `dev` | dev: API http://localhost:8090, Keycloak http://localhost:8180, client `campushire-cli`, `infrastructure/.env`. prod: `CAMPUSHIRE_PUBLIC_URL` (or `https://CAMPUSHIRE_DOMAIN`) from `deploy/.env`, Keycloak `<url>/auth`, client `campushire-loadtest` |
| `--api-url`, `--keycloak-url`, `--realm`, `--client-id` | per target | Override endpoints |
| `--admin-user`, `--admin-password` | from the env file | Keycloak master admin |
| `--tpo-email`, `--tpo-password` | `tpo@campushire.dev`, `--password` | Placement officer (created in Keycloak if missing) |
| `--password` | dev: `dev-password-1`; prod: `SEED_PASSWORD`, the previous `seed.json`, or generated | Password of every seeded account |
| `--students`, `--student-accounts`, `--companies`, `--drives` | 2000, 25, 50, 30 | Volumes |
| `--seed` | 2027 | Random seed: same seed, same students, companies and pipeline |
| `--loadtest-client` / `--remove-loadtest-client` | off | Create / delete the temporary password-grant client `campushire-loadtest` (dev/staging only) |
| `--reset` | off | See below |
| `--workers` | 12 | Parallel API calls |
| `--insecure` | auto for localhost | Accept self-signed TLS |

## Idempotency and reset

Re-running with the same arguments reuses the institute, departments, batches, companies and drives; the student
import runs again as UPSERT (all rows `updated`); accounts, invitations and claims are ensured (`alreadyLinked`).
Drives and the pipeline are only created when the institute has no drives yet.

`--reset` deletes the seeded Keycloak users (roll-number student accounts, `coordinator.*@campushire.dev`,
`*@recruiters.example.com`; never the TPO or users defined in the dev realm file) and archives the demo institute,
then exits. Organizations cannot be hard-deleted through the API: the archived institute's records, history and audit
events stay in the database (use `make infra-reset` on dev to drop everything). The next run creates a fresh
institute with the same name.

## Load-test data

```bash
python3 scripts/seed/seed.py --target prod --students 10000 --student-accounts 1500 --loadtest-client
```

Production realms have no password-grant client; `--loadtest-client` adds `campushire-loadtest` for the duration of
the test. Never leave it in a real installation: `--remove-loadtest-client` deletes it. See
[loadtest/README.md](../../loadtest/README.md).
