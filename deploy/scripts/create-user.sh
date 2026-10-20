#!/usr/bin/env bash
# Creates a Keycloak user in realm campushire (e.g. the first placement officer) with a temporary password.
#
# Usage: deploy/scripts/create-user.sh <email> <first name> <last name>
#   Prints a generated one-time password; the user must change it at first sign-in. Re-running for an existing
#   e-mail only resets the temporary password. Uses kcadm.sh inside the keycloak container (no host ports needed).
set -euo pipefail
source "$(dirname "$0")/lib.sh"

[[ $# -eq 3 && "$1" == *@* ]] || { sed -n '2,6p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
require_tools docker openssl
load_env

email="$(tr '[:upper:]' '[:lower:]' <<<"$1")"
first="$2"
last="$3"
password="$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-16)Aa1"

kcadm() { compose exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@" --config /tmp/kcadm.config; }

compose exec -T keycloak /opt/keycloak/bin/kcadm.sh config credentials --config /tmp/kcadm.config \
  --server http://localhost:8080/auth --realm master \
  --user "${KEYCLOAK_ADMIN_USER:-admin}" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null

existing="$(kcadm get users -r campushire -q "email=$email" -q exact=true --fields id --format csv --noquotes | head -1)"
if [[ -z "$existing" ]]; then
  kcadm create users -r campushire -s "username=$email" -s "email=$email" -s "firstName=$first" \
    -s "lastName=$last" -s enabled=true -s emailVerified=true >/dev/null
  log "created user $email"
else
  log "user $email exists; resetting its temporary password"
fi
kcadm set-password -r campushire --username "$email" --new-password "$password" --temporary >/dev/null

echo "Sign in at ${CAMPUSHIRE_PUBLIC_URL}/login as $email with the one-time password: $password"
