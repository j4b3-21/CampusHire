#!/usr/bin/env bash
# Creates a demo organization owned by a dev user, defines the ExampleAsset module (docs/examples) through the
# metadata API and adds two records. Everything goes through the public API, exactly like a tenant would.
# Usage: scripts/seed-demo.sh [dev-user]      Environment: API_URL (default http://localhost:8080)
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
api="${API_URL:-http://localhost:8080}"
token="$("$root/scripts/dev-token.sh" "${1:-alice}")"
auth=(-H "Authorization: Bearer $token" -H "Content-Type: application/json")

org="$(curl -fsS "${auth[@]}" -X POST "$api/api/v1/orgs" -d '{"name":"Demo Organization"}' | jq -er .id)"
curl -fsS "${auth[@]}" -X POST "$api/api/v1/orgs/$org/modules" --data @"$root/docs/examples/example-asset-module.json" >/dev/null

for record in \
  '{"data":{"name":"Laptop","serial":"LAP-001","quantity":3,"price":{"amount":"85000","currency":"INR"},"condition":"new","tags":["it"]}}' \
  '{"data":{"name":"Office chair","serial":"CHR-001","quantity":12,"price":{"amount":"120","currency":"USD"},"condition":"used","tags":["office"]}}'; do
  curl -fsS "${auth[@]}" -X POST "$api/api/v1/orgs/$org/modules/example_asset/records" -d "$record" >/dev/null
done

echo "Seeded organization $org"
echo "  curl -H \"Authorization: Bearer \$(scripts/dev-token.sh)\" $api/api/v1/orgs/$org/modules/example_asset/records"
