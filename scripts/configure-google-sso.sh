#!/usr/bin/env bash
# Enables (or updates) "Continue with Google" on a RUNNING Keycloak without resetting the realm.
#
# Reads infrastructure/.env (or the file in ENV_FILE):
#   GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET   OAuth client from Google Cloud Console (type "Web application")
#   GOOGLE_HOSTED_DOMAIN                     only accounts of this Google Workspace domain may sign in (e.g. iiitt.ac.in);
#                                            set to an empty value to allow any Google account (development only)
#   KEYCLOAK_ADMIN_USER, KEYCLOAK_ADMIN_PASSWORD, KC_HOSTNAME
#
# Google Cloud Console > APIs & Services > Credentials > OAuth client ID (Web application):
#   Authorized redirect URI: <KC_HOSTNAME>/realms/campushire/broker/google/endpoint
#   e.g. http://localhost:8180/realms/campushire/broker/google/endpoint
#
# Usage: scripts/configure-google-sso.sh [--disable]
set -euo pipefail

for tool in curl jq; do
  command -v "$tool" >/dev/null || { echo "configure-google-sso.sh: $tool is required" >&2; exit 1; }
done

root="$(cd "$(dirname "$0")/.." && pwd)"
env_file="${ENV_FILE:-$root/infrastructure/.env}"
[[ -f "$env_file" ]] || { echo "configure-google-sso.sh: $env_file not found (copy infrastructure/.env.example)" >&2; exit 1; }

set -a
# shellcheck disable=SC1090
source "$env_file"
set +a

keycloak="${KC_HOSTNAME:-http://localhost:8180}"
realm="${KEYCLOAK_REALM:-campushire}"
enabled=true
[[ "${1:-}" == "--disable" ]] && enabled=false

if [[ "$enabled" == true ]]; then
  if [[ -z "${GOOGLE_CLIENT_ID:-}" || "$GOOGLE_CLIENT_ID" == change-me* \
     || -z "${GOOGLE_CLIENT_SECRET:-}" || "$GOOGLE_CLIENT_SECRET" == change-me* ]]; then
    echo "configure-google-sso.sh: set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET in $env_file first" >&2
    exit 1
  fi
fi

admin_token="$(curl -sS -X POST "$keycloak/realms/master/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=admin-cli \
  --data-urlencode "username=${KEYCLOAK_ADMIN_USER:?}" --data-urlencode "password=${KEYCLOAK_ADMIN_PASSWORD:?}" \
  | jq -er '.access_token')" || { echo "configure-google-sso.sh: Keycloak admin login failed at $keycloak" >&2; exit 1; }

api="$keycloak/admin/realms/$realm/identity-provider/instances/google"
current="$(curl -sS -f -H "Authorization: Bearer $admin_token" "$api")" \
  || { echo "configure-google-sso.sh: identity provider 'google' not found in realm $realm" >&2; exit 1; }

if [[ "$enabled" == true ]]; then
  updated="$(jq --arg id "$GOOGLE_CLIENT_ID" --arg secret "$GOOGLE_CLIENT_SECRET" \
    --arg domain "${GOOGLE_HOSTED_DOMAIN:-}" \
    '.enabled = true | .config.clientId = $id | .config.clientSecret = $secret | .config.hostedDomain = $domain' \
    <<<"$current")"
else
  updated="$(jq '.enabled = false' <<<"$current")"
fi

curl -sS -f -X PUT -H "Authorization: Bearer $admin_token" -H "Content-Type: application/json" \
  --data "$updated" "$api" >/dev/null

if [[ "$enabled" == true ]]; then
  echo "Google sign-in enabled for realm $realm (hosted domain: ${GOOGLE_HOSTED_DOMAIN:-any})."
  echo "Redirect URI to register in Google Cloud: $keycloak/realms/$realm/broker/google/endpoint"
else
  echo "Google sign-in disabled for realm $realm."
fi
