# Rate limiting

The `ratelimit` module protects the platform from runaway or abusive clients by limiting **mutating API calls per
authenticated identity**. It is implemented as a servlet filter backed by Redis.

## What is limited

A request is counted only when **all** of the following hold:

- the URI starts with `/api/`;
- the method is `POST`, `PUT`, `PATCH` or `DELETE`;
- the caller is authenticated with a JWT bearer token (`JwtAuthenticationToken`).

Reads (`GET`, `HEAD`, `OPTIONS`), actuator/docs endpoints and unauthenticated requests are never counted. The filter
runs after the Spring Security filter chain (order `SecurityFilterProperties.DEFAULT_FILTER_ORDER + 10`), so requests
rejected by authentication never reach it.

Every counted request carries `X-RateLimit-Limit` and `X-RateLimit-Remaining`. When the limit is exceeded the
request is not processed and the response is `429 Too Many Requests` with a `Retry-After` header (seconds until the
current window ends) and an RFC 9457 problem body with `"code": "rate_limited"`.

## Key design

- **Fixed window.** Key: `bm4a:rl:w:<sha256hex(iss + "|" + sub)>:<epochSecond / windowSeconds>`. Hashing keeps
  subjects and issuer URLs out of Redis; including the issuer keeps identities from different realms apart.
- **Atomic counting.** A small Lua script runs `INCR` and, on the first hit of a window, `EXPIRE` with twice the
  window length, so keys always expire and a crash between the two commands cannot leave an immortal counter.
- A fixed window allows a burst of up to 2x the limit across a window boundary. That is acceptable for abuse
  protection; it is not a billing-grade quota.

## Fail-open rationale

Redis is a cache, not a source of truth. If the script fails (connection refused, timeout, any runtime exception),
the request is **let through**, the `bm4a.ratelimit.errors` counter is incremented and a `WARN` is logged at most
once per minute per instance. A Redis outage therefore degrades protection, never availability. While Redis is
failing, responses carry `X-RateLimit-Limit` but no `X-RateLimit-Remaining`.

## Configuration

| Property | Env variable | Default | Meaning |
| --- | --- | --- | --- |
| `bm4a.ratelimit.enabled` | `RATE_LIMIT_ENABLED` | `true` (code default `false`) | Registers the filter at all |
| `bm4a.ratelimit.writes-per-minute` | `RATE_LIMIT_WRITES_PER_MINUTE` | `300` | Allowed mutating requests per identity per window (min 1) |
| `bm4a.ratelimit.window` | — | `60s` | Window length (whole seconds) |

Redis connection: `spring.data.redis.host` / `port` (`REDIS_HOST`, `REDIS_PORT`). The `test` profile disables rate
limiting; `RateLimitIntegrationTest` enables it in its own context with a Redis container.

## Metrics

| Meter | Type | Meaning |
| --- | --- | --- |
| `bm4a.ratelimit.rejected` | counter | Requests answered with 429 |
| `bm4a.ratelimit.errors` | counter | Checks that failed and were let through (fail open) |

All meters carry the `application` tag (`management.metrics.tags.application`). Log lines include the request's
correlation ID via `logging.pattern.correlation`.

## Tuning

- Watch `bm4a.ratelimit.rejected`: a steady non-zero rate from legitimate users means the limit is too low (or a
  client is retrying in a loop - check by correlation ID in the logs).
- Any sustained `bm4a.ratelimit.errors` means Redis is unreachable from the platform and limiting is effectively off.
- Bulk/import clients should be given their own endpoints or a higher limit rather than disabling limiting globally.
- `writes-per-minute` is the per-window limit; if you change `window`, the limit applies to the new window length.
