#!/usr/bin/env bash
# Restores a snapshot written by backup.sh. DESTRUCTIVE: replaces the databases and the bucket contents.
#
# Usage: deploy/scripts/restore.sh <backup-dir> [--yes] [--only databases|files]
#   Stops nginx, both API replicas, Keycloak and OpenFGA, recreates each database from <db>.dump, mirrors
#   <backup-dir>/minio back into the bucket (removing objects that are not in the backup), then starts everything.
# Example: deploy/scripts/restore.sh /var/backups/campushire/20261020T023000Z
set -euo pipefail
source "$(dirname "$0")/lib.sh"

usage() { sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }

[[ $# -ge 1 && "$1" != -* ]] || usage
source_dir="$(cd "$1" && pwd)" || die "backup directory $1 not found"
shift
assume_yes=false
only=all
while [[ $# -gt 0 ]]; do
  case "$1" in
    --yes) assume_yes=true ;;
    --only) only="${2:-}"; shift ;;
    *) usage ;;
  esac
  shift
done
[[ "$only" == all || "$only" == databases || "$only" == files ]] || usage

require_tools docker
load_env

if [[ -f "$source_dir/SHA256SUMS" ]]; then
  (cd "$source_dir" && { sha256sum -c --quiet SHA256SUMS 2>/dev/null || shasum -a 256 -c --quiet SHA256SUMS; }) \
    || die "checksum verification failed for $source_dir"
fi

if [[ "$assume_yes" != true ]]; then
  printf 'Restore %s (%s) over the running stack? Current data will be lost. [y/N] ' "$source_dir" "$only"
  read -r answer
  [[ "$answer" == y || "$answer" == Y || "$answer" == yes ]] || die "aborted"
fi

log "stopping traffic and database clients"
compose stop nginx frontend backend-1 backend-2 keycloak openfga >/dev/null
compose up -d --wait postgres minio >/dev/null

if [[ "$only" != files ]]; then
  for pair in "${PLATFORM_DB:-campushire}:${PLATFORM_DB_USER:-campushire}" \
              "${KEYCLOAK_DB:-keycloak}:${KEYCLOAK_DB_USER:-keycloak}" \
              "${OPENFGA_DB:-openfga}:${OPENFGA_DB_USER:-openfga}"; do
    db="${pair%%:*}"
    owner="${pair#*:}"
    dump="$source_dir/$db.dump"
    [[ -s "$dump" ]] || die "$dump missing"
    log "restoring database $db (owner $owner)"
    compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres \
      -c "DROP DATABASE IF EXISTS \"$db\" WITH (FORCE)" -c "CREATE DATABASE \"$db\" OWNER \"$owner\"" \
      -c "REVOKE ALL ON DATABASE \"$db\" FROM PUBLIC" >/dev/null
    compose exec -T postgres pg_restore -U "$POSTGRES_USER" -d "$db" --no-owner --role="$owner" \
      --exit-on-error <"$dump"
  done
  compose exec -T postgres psql -U "$POSTGRES_USER" -d "${PLATFORM_DB:-campushire}" \
    -c 'CREATE EXTENSION IF NOT EXISTS pg_stat_statements' >/dev/null || true
fi

if [[ "$only" != databases && -d "$source_dir/minio" ]]; then
  log "restoring bucket ${S3_BUCKET:-campushire-files}"
  docker run --rm --network campushire-prod --user "$(id -u):$(id -g)" \
    -e MC_CONFIG_DIR=/tmp/.mc \
    -e "MC_HOST_dst=http://${MINIO_ROOT_USER}:${MINIO_ROOT_PASSWORD}@minio:9000" \
    -v "$source_dir/minio:/backup:ro" \
    pgsty/mc:RELEASE.2026-10-18T00-00-00Z \
    mirror --overwrite --remove --quiet /backup "dst/${S3_BUCKET:-campushire-files}" >/dev/null
fi

log "starting the stack"
compose up -d --wait nginx
log "restore of $source_dir complete; run deploy/scripts/smoke-test.sh"
