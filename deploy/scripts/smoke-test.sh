#!/usr/bin/env bash
# Post-deploy smoke test through nginx: container health, HTTP->HTTPS redirect, web app (/ and /login), Keycloak
# discovery and login page, API 401 without a token, actuator hidden from the edge (404).
#
# Usage: deploy/scripts/smoke-test.sh [--insecure]
#   --insecure          accept a self-signed certificate (also SMOKE_INSECURE=1)
#   SMOKE_RESOLVE=IP    connect to IP instead of resolving CAMPUSHIRE_DOMAIN (default 127.0.0.1; "dns" = real DNS)
#   SMOKE_ATTEMPTS=N    retries per check while services settle (default 10, 3 s apart)
set -euo pipefail
source "$(dirname "$0")/lib.sh"

insecure="${SMOKE_INSECURE:-0}"
case "${1:-}" in
  "") ;;
  --insecure) insecure=1 ;;
  -h|--help) sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2 ;;
esac

require_tools curl docker
load_env
public="${CAMPUSHIRE_PUBLIC_URL%/}"
domain="${CAMPUSHIRE_DOMAIN}"
https_port="${HTTPS_PORT:-443}"
http_port="${HTTP_PORT:-80}"
resolve_ip="${SMOKE_RESOLVE:-127.0.0.1}"
attempts="${SMOKE_ATTEMPTS:-10}"

curl_opts=(-sS --max-time 15)
[[ "$insecure" == 1 ]] && curl_opts+=(-k)
if [[ "$resolve_ip" != dns ]]; then
  curl_opts+=(--resolve "$domain:$https_port:$resolve_ip" --resolve "$domain:$http_port:$resolve_ip")
fi

failures=0
pass() { echo "  ok    $*"; }
fail() { echo "  FAIL  $*"; failures=$((failures + 1)); }

# check <description> <expected status|status...> <url> [grep pattern]
check() {
  local desc="$1" expected="$2" url="$3" pattern="${4:-}" body status i
  body="$(mktemp)"
  for ((i = 1; i <= attempts; i++)); do
    status="$(curl "${curl_opts[@]}" -o "$body" -w '%{http_code}' "$url" 2>/dev/null || echo 000)"
    if [[ "|$expected|" == *"|$status|"* ]] && { [[ -z "$pattern" ]] || grep -q -- "$pattern" "$body"; }; then
      pass "$desc ($status)"
      rm -f "$body"
      return
    fi
    sleep 3
  done
  fail "$desc: expected $expected${pattern:+ containing '$pattern'}, got $status"
  rm -f "$body"
}

echo "CampusHire smoke test against $public"

echo "containers:"
for service in nginx frontend backend-1 backend-2 keycloak postgres openfga redis minio; do
  id="$(compose ps -q "$service" 2>/dev/null || true)"
  health="$( [[ -n "$id" ]] && docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" || echo missing)"
  if [[ "$health" == healthy ]]; then pass "$service healthy"; else fail "$service is $health"; fi
done

echo "edge:"
http_base="http://$domain"; [[ "$http_port" != 80 ]] && http_base+=":$http_port"
check "nginx health" 200 "$http_base/nginx-health" ok
check "HTTP redirects to HTTPS" 301 "$http_base/app"
check "web app root (page or redirect to /app)" "200|307|308" "$public/"
check "web app login page" 200 "$public/login"
check "issuer in OIDC discovery" 200 "$public/auth/realms/campushire/.well-known/openid-configuration" \
  "\"issuer\":\"$public/auth/realms/campushire\""
check "Keycloak login form" 200 \
  "$public/auth/realms/campushire/protocol/openid-connect/auth?client_id=campushire-web&response_type=code&scope=openid&redirect_uri=$public/app&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256" \
  'kc-form-login\|id="username"'
check "API rejects requests without a token" 401 "$public/api/v1/me"
check "actuator hidden from the edge" 404 "$public/actuator/health"

if [[ "$failures" -gt 0 ]]; then
  echo "smoke test FAILED ($failures checks)"
  exit 1
fi
echo "smoke test passed"
