#!/usr/bin/env bash
# Prints a Keycloak access token for a DEVELOPMENT realm user (password grant on the dev-only bm4a-cli client).
# Usage: scripts/dev-token.sh [alice|bob|platform-admin]
# Environment: KEYCLOAK_URL (default http://localhost:2346), KEYCLOAK_REALM (default bm4a)
set -euo pipefail

user="${1:-alice}"
case "$user" in
  alice) password="alice-dev-password" ;;
  bob) password="bob-dev-password" ;;
  platform-admin) password="admin-dev-password" ;;
  *) echo "unknown dev user: $user" >&2; exit 2 ;;
esac

keycloak="${KEYCLOAK_URL:-http://localhost:2346}"
realm="${KEYCLOAK_REALM:-bm4a}"

response="$(curl -fsS -X POST "$keycloak/realms/$realm/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=bm4a-cli -d scope=openid \
  --data-urlencode "username=$user" --data-urlencode "password=$password")"
jq -er '.access_token' <<<"$response"
