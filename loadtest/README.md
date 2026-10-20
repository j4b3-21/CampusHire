# CampusHire load tests (k6)

k6 scenarios for the [ADR-006](../docs/architecture/ADR-006-scaling-to-10k-users.md) capacity plan. They run in the
`grafana/k6` container and send all traffic through nginx (TLS, rate limits, `least_conn` across both API replicas),
exactly like browsers.

> **Status: not executed yet.** The scripts are written and reviewed, and each request they send was checked once
> against the dev stack. The full test has not been run: the owner decided not to run heavy workloads on the
> development laptop. Run it on the reference server or a staging copy and record the results in
> [docs/operations/load-test.md](../docs/operations/load-test.md).

| Scenario | Users | What each user does | Thresholds (ADR-006) |
|----------|-------|---------------------|----------------------|
| `drive-spike.js` | `VUS` students (1,500), ramping-vus: 0 to 1,500 over `RAMP` (3m), hold `HOLD` (5m), ramp down `RAMP_DOWN` (1m) | Own Keycloak session (password grant, then refresh). Every 3-5 s: open drives list, drive detail, apply to an eligible drive (idempotent: `201`, then `200`), dashboard, unread notification count | `http_req_duration{kind:read}` p95 < 300 ms, `{name:apply}` p95 < 500 ms, `http_req_failed{api:yes}` < 0.1 % |
| `browse.js` | `VUS` staff sessions (100) shared by the TPO and the 6 coordinators | Weighted mix: student lists filtered by department / CGPA / backlogs with sort and search, companies, drives, applications by stage, drive applicants, analytics overview / departments / companies, dashboard, eligibility preview | reads p95 < 300 ms, eligibility preview (`kind:heavy`) p95 < 1.5 s, failed < 0.1 % |
| `staff.js` | 1 placement officer, `ITERATIONS` (3) | Imports `ROWS` (1,000) student rows as CSV (UPSERT on roll number) and polls the job to completion; exports all students (XLSX, CSV), one department (XLSX) and the placement report | import job p95 < 60 s, exports p95 < 15 s |

Every request is tagged `name` (endpoint) and `kind` (`read`, `write`, `heavy`, `export`, `auth`), so the summary
breaks latency down per endpoint. Keycloak token calls are `kind:auth` and are not part of the API thresholds.

## Files

| File | Purpose |
|------|---------|
| `lib/auth.js` | One token per VU via the password grant of a dedicated load-test client; refresh before expiry |
| `lib/common.js` | Base URLs, seed data (`data/seed.json`), request tags, summary output |
| `run.sh` | `docker run --rm -i --network host grafana/k6 run ...` wrapper; writes `results/` |
| `monitor.sh` | Samples `docker stats` and Hikari pool metrics during a run; `--report` prints peaks and slow SQL |
| `data/`, `results/` | Git-ignored. `data/seed.json` contains the seeded accounts' password |

## Procedure

Run on the 8 vCPU / 16 GB reference server or a staging copy, never against a live installation with real
students. The load-test client is a password-grant client and must not stay in a production realm.

```bash
# 1. Production stack (deploy/.env, docs/operations/DEPLOYMENT.md); optional monitoring profile
make prod-up MONITORING=1           # set BACKEND_PROMETHEUS_PATH=health/prometheus in deploy/.env first
make prod-smoke

# 2. Data: 10,000 students imported through the data-exchange API, 1,500 student accounts that claim their
#    records, the temporary Keycloak client campushire-loadtest (password grant). Takes a few minutes.
python3 scripts/seed/seed.py --target prod --students 10000 --student-accounts 1500 --loadtest-client
#    (same as: make seed-load)

# 3. Scenarios (one at a time; watch resources from a second shell with: loadtest/monitor.sh spike)
make load-spike                     # = loadtest/run.sh drive-spike
make load-browse
make load-staff

# 4. Clean up
loadtest/monitor.sh --report spike
python3 scripts/seed/seed.py --target prod --remove-loadtest-client
```

`run.sh` runs `docker run --rm -i --network host grafana/k6 run --summary-export ... /loadtest/<scenario>.js`.
Useful variables:

| Variable | Default | Meaning |
|----------|---------|---------|
| `BASE_URL` | `CAMPUSHIRE_PUBLIC_URL` or `https://CAMPUSHIRE_DOMAIN` from `deploy/.env` | Public URL (Keycloak at `<BASE_URL>/auth`) |
| `K6_HOST_IP` | unset | Pins the domain to an IP inside the container (`127.0.0.1` when k6 runs on the server) |
| `K6_NETWORK` | `host` | `campushire-prod` with `BASE_URL=https://nginx` keeps traffic on the internal network |
| `INSECURE` | `true` | Accept self-signed certificates |
| `VUS`, `RAMP`, `HOLD`, `RAMP_DOWN` | `1500`, `3m`, `5m`, `1m` | drive-spike shape |
| `THINK_MIN`, `THINK_MAX` | `3`, `5` | Seconds between a student's requests |
| `K6_IMAGE` | `grafana/k6:latest` | Pin a version for comparable runs |
| `K6_DASHBOARD` | `0` | `1` writes an HTML report next to the summary |

Prefer a separate load-generator machine on the campus network (`BASE_URL=https://placements.example.edu`). k6 on
the server itself takes 1-2 cores away from the stack and makes results pessimistic. Start with
`VUS=300 HOLD=2m` to check the setup before the full spike.

## Reading the results

- `results/<scenario>-<stamp>.summary.txt` is the k6 end-of-test summary. A threshold line marked `✗` fails the
  run, and k6 exits non-zero.
- Per-endpoint latency: `http_req_duration{name:student_drives}`, `{name:apply}` and so on in
  `results/<scenario>-<stamp>.json` (`metrics` object, keys with tags).
- `applications_created` vs `applications_existing`: first applies vs idempotent repeats. `apply_conflicts` counts
  `409`s (drive closed, rule changed) and is not an error. `login_failures` should be 0.
- Errors: `http_req_failed{api:yes}` counts API responses outside 2xx/3xx (apply also accepts 409). nginx `429`s
  appear here; check the nginx access log (`status: 429`) and raise `NGINX_API_RATE` / `NGINX_API_IP_RATE` if the
  generator's single IP hits the per-IP limit.
- Correlate with `loadtest/monitor.sh` output (CPU per container, Hikari `pending > 0` means the pool is the
  bottleneck) and the Grafana dashboard "CampusHire API" (`/grafana/`, monitoring profile).

## Expected capacity (ADR-006 arithmetic, to be confirmed by measurement)

| Quantity | Estimate |
|----------|----------|
| Request rate at 1,500 VUs | 1 request per 3-5 s per student: 300-500 req/s (about 375 req/s average) |
| Applies | at most one per student per drive; about 75/s during the ramp, then `200` repeats |
| Logins / refreshes | 1,500 logins over the 3-minute ramp (about 8/s); refreshes about 6/s (5-minute tokens) |
| DB concurrency | Little's law: 400 req/s x 10-50 ms DB time = 4-20 busy connections, well below the 60 Hikari connections (2 x 30) |
| API CPU | about 200 req/s per replica; at 5-10 ms CPU per request, 1-2 of each replica's 3 cores |
| PostgreSQL | 10k students, 200k applications: the working set fits in 4 GB `shared_buffers` |
| nginx limits | per token 50 r/s vs 0.25 r/s per student; per IP 2,000 r/s vs at most 500 r/s from one generator |

If these hold, the reference host serves the spike with p95 latencies far below the targets. The likely first
limits are Keycloak during the login burst (CPU-bound password hashing) and JSON serialization on the API replicas.

## Results template

Copy into [docs/operations/load-test.md](../docs/operations/load-test.md) for every run.

| Date | Commit | Host (vCPU / RAM) | Scenario | Peak VUs | RPS | Reads p50 / p95 / p99 (ms) | Apply p95 (ms) | Errors | Thresholds | Notes |
|------|--------|-------------------|----------|----------|-----|----------------------------|----------------|--------|------------|-------|
| | | | drive-spike | | | | | | | |
| | | | browse | | | | | | | |
| | | | staff | | | import p95: | export p95: | | | |

## Notes

- k6 has no built-in SSE client, so the notification stream is not exercised. `unread-count` polling models the
  same read load.
- `apply` is sent only for drives the list reports as eligible and open, as the web app does.
