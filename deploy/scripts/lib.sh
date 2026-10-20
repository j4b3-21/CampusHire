# Shared helpers for deploy/scripts (sourced, not executed).
# shellcheck shell=bash

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC2034  # used by the scripts that source this file
REPO_DIR="$(cd "$DEPLOY_DIR/.." && pwd)"
ENV_FILE="${ENV_FILE:-$DEPLOY_DIR/.env}"
COMPOSE_FILE_PATH="$DEPLOY_DIR/docker-compose.prod.yml"

die() { echo "$(basename "$0"): $*" >&2; exit 1; }
log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

require_tools() {
  local tool
  for tool in "$@"; do
    command -v "$tool" >/dev/null 2>&1 || die "$tool is required"
  done
}

load_env() {
  [[ -f "$ENV_FILE" ]] || die "$ENV_FILE not found (cp deploy/.env.example deploy/.env and edit it)"
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
  [[ -n "${CAMPUSHIRE_DOMAIN:-}" ]] || die "CAMPUSHIRE_DOMAIN is not set in $ENV_FILE"
  CAMPUSHIRE_PUBLIC_URL="${CAMPUSHIRE_PUBLIC_URL:-https://$CAMPUSHIRE_DOMAIN}"
}

compose() {
  docker compose -f "$COMPOSE_FILE_PATH" --env-file "$ENV_FILE" "$@"
}

# Runs curl inside the stack network (services are not published on the host).
net_curl() {
  docker run --rm --network campushire-prod "${CURL_IMAGE:-curlimages/curl:latest}" "$@"
}
