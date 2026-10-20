#!/usr/bin/env bash
# End-to-end smoke test against a running stack (infrastructure compose + platform), using real Keycloak tokens
# and the real OpenFGA. Exercises the first-milestone acceptance criteria and exits non-zero on the first failure.
# Usage: scripts/smoke-test.sh      Environment: API_URL (default http://localhost:8080), KEYCLOAK_URL
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
api="${API_URL:-http://localhost:8080}"
body="$(mktemp)"
headers="$(mktemp)"
trap 'rm -f "$body" "$headers"' EXIT
passed=0

# call <token|-> <method> <path> [json] [if-match]  -> sets $status; response body in $body, headers in $headers
call() {
  local token="$1" method="$2" path="$3" data="${4:-}" ifmatch="${5:-}"
  local args=(-sS -o "$body" -D "$headers" -w '%{http_code}' -X "$method" "$api$path")
  [[ "$token" != "-" ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n "$data" ]] && args+=(-H "Content-Type: application/json" --data "$data")
  [[ -n "$ifmatch" ]] && args+=(-H "If-Match: $ifmatch")
  status="$(curl "${args[@]}")"
}

expect() {
  local expected="$1" description="$2"
  if [[ "$status" != "$expected" ]]; then
    echo "FAIL: $description (expected HTTP $expected, got $status)" >&2
    cat "$body" >&2; echo >&2
    exit 1
  fi
  passed=$((passed + 1))
  echo "ok   $description"
}

json() { jq -er "$1" "$body"; }
etag() { grep -i '^etag:' "$headers" | tr -d '\r' | cut -d' ' -f2; }

echo "Smoke testing $api"
call - GET /actuator/health/readiness;                 expect 200 "readiness probe is UP"
call - GET /api/v1/me;                                 expect 401 "API rejects anonymous requests"
call "not-a-token" GET /api/v1/me;                     expect 401 "API rejects malformed tokens"

alice="$("$root/scripts/dev-token.sh" alice)"
bob="$("$root/scripts/dev-token.sh" bob)"

call "$alice" GET /api/v1/me;                          expect 200 "Keycloak token is accepted (alice)"
call "$bob" GET /api/v1/me;                            expect 200 "Keycloak token is accepted (bob)"
bob_id="$(json .id)"

call "$alice" POST /api/v1/orgs '{"name":"Smoke Test Org"}'; expect 201 "alice creates an organization"
org="$(json .id)"
call "$bob" GET "/api/v1/orgs/$org";                   expect 404 "bob cannot see alice's organization"

call "$alice" POST "/api/v1/orgs/$org/modules" "$(cat "$root/docs/examples/example-asset-module.json")"
expect 201 "alice defines the ExampleAsset module through the metadata API"

records="/api/v1/orgs/$org/modules/example_asset/records"
call "$alice" POST "$records" '{"data":{"quantity":-1}}'
expect 400 "invalid payload is rejected"
[[ "$(json '[.errors[].field] | sort | join(",")')" == "data.name,data.quantity" ]] || { echo "FAIL: field errors" >&2; exit 1; }

call "$alice" POST "$records" '{"data":{"name":"Laptop","serial":"SMK-1","price":{"amount":"999.5","currency":"USD"},"tags":["it"]}}'
expect 201 "alice creates a record"
id="$(json .id)"
[[ "$(json .data.price.amount)" == "999.50" && "$(json .data.quantity)" == "1" ]] || { echo "FAIL: canonical values/defaults" >&2; exit 1; }

call "$alice" PATCH "$records/$id" '{"data":{"quantity":4}}'
expect 428 "update without If-Match is rejected"
call "$alice" PATCH "$records/$id" '{"data":{"quantity":4}}' '"1"'
expect 200 "update with current version succeeds"
call "$alice" PATCH "$records/$id" '{"data":{"quantity":5}}' '"1"'
expect 412 "stale version is rejected"

call "$alice" GET "$records?filter%5Bquantity%5D%5Bgte%5D=4&sort=-quantity"
expect 200 "filtered listing works"
[[ "$(json .totalElements)" == "1" ]] || { echo "FAIL: filter result" >&2; exit 1; }

call "$bob" GET "$records/$id";                        expect 404 "bob cannot read the record"
call "$bob" POST /api/v1/orgs '{"name":"Bob Org"}';   expect 201 "bob creates his own organization"
bob_org="$(json .id)"
call "$bob" GET "/api/v1/orgs/$bob_org/modules/example_asset/records/$id"; expect 404 "record ID is not reachable via another tenant"

call "$alice" POST "/api/v1/orgs/$org/members" "{\"userId\":\"$bob_id\",\"role\":\"MEMBER\"}"
expect 201 "alice adds bob as a member (OpenFGA tuple written)"
call "$bob" GET "$records/$id";                        expect 200 "bob can read as a member"
call "$bob" POST "/api/v1/orgs/$org/modules" '{"apiKey":"x","labelSingular":"X","labelPlural":"Xs"}'
expect 403 "members cannot change metadata"

call "$alice" DELETE "$records/$id" "" '"2"';          expect 200 "soft delete"
call "$alice" GET "$records/$id";                      expect 404 "deleted record is hidden"
call "$alice" POST "$records/$id/restore" "" '"3"';    expect 200 "restore"
call "$alice" GET "$records/$id/versions";             expect 200 "history is available"
[[ "$(json .totalElements)" == "4" ]] || { echo "FAIL: history length" >&2; exit 1; }

call "$alice" DELETE "/api/v1/orgs/$org/members/$bob_id"; expect 204 "alice revokes bob"
call "$bob" GET "$records/$id";                        expect 404 "revocation is immediate"

call "$alice" GET "/api/v1/orgs/$org/audit-events?size=100"; expect 200 "audit trail is readable by the owner"
for action in organization.created metadata.module.created record.created record.updated record.deleted \
              record.restored membership.granted membership.revoked; do
  jq -e --arg a "$action" '[.items[].action] | index($a) != null' "$body" >/dev/null \
    || { echo "FAIL: audit action $action missing" >&2; exit 1; }
done
passed=$((passed + 1)); echo "ok   audit events recorded for all mutations"

echo "PASS: $passed checks"
