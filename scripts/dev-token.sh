#!/usr/bin/env bash
# Prints a Keycloak access token for a DEVELOPMENT realm user (password grant on the dev-only campushire-cli client).
# Usage: scripts/dev-token.sh <email> [password]        (password defaults to dev-password-1)
# Environment: KEYCLOAK_URL (default http://localhost:8180), KEYCLOAK_REALM (default campushire)
# Dev users: tpo@campushire.dev, coordinator.cse@campushire.dev, student1@iiitt.ac.in, student2@iiitt.ac.in,
#            recruiter@acme.dev, admin@campushire.dev
set -euo pipefail

if [[ $# -lt 1 || "$1" == "-h" || "$1" == "--help" ]]; then
  sed -n '2,6p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 2
fi

for tool in curl jq; do
  command -v "$tool" >/dev/null || { echo "dev-token.sh: $tool is required" >&2; exit 1; }
done

email="$1"
password="${2:-dev-password-1}"
keycloak="${KEYCLOAK_URL:-http://localhost:8180}"
realm="${KEYCLOAK_REALM:-campushire}"

response="$(curl -sS -X POST "$keycloak/realms/$realm/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=campushire-cli -d scope=openid \
  --data-urlencode "username=$email" --data-urlencode "password=$password")"

if ! token="$(jq -er '.access_token' <<<"$response" 2>/dev/null)"; then
  echo "dev-token.sh: token request for $email failed: $response" >&2
  exit 1
fi
echo "$token"
