# Load test: 10,000 users on one host

> **The full load test has NOT been executed yet.** The owner decided not to run heavy workloads (production stack,
> image builds, k6) on the development laptop. The scenarios in [loadtest/](../../loadtest/README.md) are written and
> reviewed, and every request they send was checked once against the dev stack. The capacity figures below are the
> [ADR-006](../architecture/ADR-006-scaling-to-10k-users.md) design arithmetic, not measurements. Fill in the results
> table after running the procedure on the reference server or a staging copy.

## Targets (ADR-006)

| Metric | Target |
|--------|--------|
| Read requests (lists, details, dashboard, notifications) | p95 < 300 ms |
| Apply to a drive | p95 < 500 ms |
| Error rate (API responses outside 2xx/3xx; apply also accepts 409) | < 0.1 % |
| Design peak | 1,500 students active within ~3 minutes, one request every 3-5 s each (300-500 req/s) |

## Environment to record

| Item | Value |
|------|-------|
| Host | 8 vCPU / 16 GB RAM / SSD (reference), OS, Docker version |
| Commit / `IMAGE_TAG` | |
| Configuration | `deploy/.env` sizing (`PG_*`, `*_MEM_LIMIT`, `DB_POOL_SIZE`, `NGINX_*`) |
| Data | `seed.py --students 10000 --student-accounts 1500` (import duration and rows/s from its output) |
| Load generator | separate machine or the server itself (k6 shares its CPU), k6 version |

## Procedure

```bash
# On the server (or staging copy), with deploy/.env configured per DEPLOYMENT.md:
make prod-up MONITORING=1                  # Prometheus + Grafana for API, Keycloak and OpenFGA metrics
make prod-smoke
make seed-load                             # 10,000 students, 1,500 accounts, temporary client campushire-loadtest

loadtest/monitor.sh spike &                # resources and Hikari pools, loadtest/results/spike-*.tsv
VUS=300 HOLD=2m make load-spike            # warm-up and setup check
make load-spike                            # full spike: ramp to 1,500 VUs over 3 min, hold 5 min, ramp down
kill %1 && loadtest/monitor.sh --report spike

loadtest/monitor.sh browse & make load-browse; kill %1 && loadtest/monitor.sh --report browse
make load-staff

python3 scripts/seed/seed.py --target prod --remove-loadtest-client
```

Keep `loadtest/results/*.summary.txt` (git-ignored) with the run notes, then copy the key figures below.

## Capacity estimate (to be verified)

| Quantity | Arithmetic |
|----------|------------|
| API request rate | 1,500 students / one request every 3-5 s = 300-500 req/s |
| Per replica | ~200 req/s; at 5-10 ms CPU per request, 1-2 of the 3 cores per replica |
| Database connections | 400 req/s x 10-50 ms DB time = 4-20 concurrent (Little's law) of 60 Hikari connections; the total budget 60 + Keycloak 20 + OpenFGA 20 = 100 of `max_connections` 200 |
| Hot write path | apply: one short transaction, `INSERT ... ON CONFLICT DO NOTHING`, no drive row lock; at most ~75 first applies/s during the ramp |
| Keycloak | ~8 logins/s during the ramp, ~6 refreshes/s afterwards (ADR-006 budget: ~35/s for 10k users) |
| Memory | working set (10k students, 200k applications/year) fits in `shared_buffers=4GB` |
| Edge | per-token limit 50 r/s vs ~0.25 r/s per student; per-IP 2,000 r/s vs ≤ 500 r/s from a single generator |

Expected first bottlenecks: Keycloak CPU during the login burst (password hashing), then API CPU (JSON
serialization, OpenFGA checks). Hikari `pending > 0` in `monitor.sh` or Grafana means the pool, not the CPU, is
limiting.

## Results

No run recorded yet.

| Date | Commit | Host (vCPU / RAM) | Scenario | Peak VUs | RPS | Reads p50 / p95 / p99 (ms) | Apply p95 (ms) | Errors | Thresholds | Notes |
|------|--------|-------------------|----------|----------|-----|----------------------------|----------------|--------|------------|-------|
| | | | drive-spike | | | | | | | |
| | | | browse | | | | | | | |
| | | | staff | | | import p95: | export p95: | | | |

Per-endpoint p95 (drive-spike):

| Endpoint (`name` tag) | p95 (ms) | Target |
|-----------------------|----------|--------|
| `student_drives` | | 300 |
| `drive_detail` | | 300 |
| `apply` | | 500 |
| `dashboard` | | 300 |
| `unread_count` | | 300 |

Peak resources (from `loadtest/monitor.sh --report`):

| Container | Peak CPU % | Peak memory (MiB) |
|-----------|------------|-------------------|
| backend-1 / backend-2 | | |
| postgres | | |
| keycloak | | |
| nginx | | |

## Known limitations of the scenarios

- No SSE client in k6: the notification stream is modelled by `unread-count` polling.
- One generator IP: nginx's per-IP limit (`NGINX_API_IP_RATE`) must stay above the generated rate.
- The production realm has no password-grant client. `seed.py --loadtest-client` adds `campushire-loadtest` for the
  run, and `--remove-loadtest-client` deletes it.
