#!/usr/bin/env bash
# Backs up the production stack: one compressed pg_dump per database (+ roles) and a MinIO mirror.
#
# Usage: deploy/scripts/backup.sh [--retention-days N] [--no-files]
#   Writes BACKUP_DIR/<UTC timestamp>/ (postgres-globals.sql.gz, <db>.dump, minio/, SHA256SUMS, MANIFEST) and
#   deletes snapshots older than N days (default BACKUP_RETENTION_DAYS or 14). BACKUP_DIR defaults to
#   /var/backups/campushire. Objects are mirrored incrementally into BACKUP_DIR/minio-current; each snapshot copies
#   that state with rsync --link-dest, so unchanged files are hard links to the previous snapshot.
#   Exits 0 without doing anything when the stack is not running yet (first deploy).
# Cron (daily 02:30): 30 2 * * * cd /opt/campushire && deploy/scripts/backup.sh >>/var/log/campushire-backup.log 2>&1
set -euo pipefail
source "$(dirname "$0")/lib.sh"

usage() { sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; }

with_files=true
retention=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-files) with_files=false ;;
    --retention-days) retention="${2:-}"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
  shift
done

require_tools docker gzip
load_env
backup_root="${BACKUP_DIR:-/var/backups/campushire}"
retention="${retention:-${BACKUP_RETENTION_DAYS:-14}}"
[[ "$retention" =~ ^[0-9]+$ ]] || die "--retention-days must be a number of days"

if [[ -z "$(compose ps --status running -q postgres 2>/dev/null)" ]]; then
  log "postgres is not running; nothing to back up"
  exit 0
fi

mkdir -p "$backup_root"
exec 9>"$backup_root/.lock"
if command -v flock >/dev/null 2>&1; then
  flock -n 9 || die "another backup is running"
fi

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
target="$backup_root/$stamp"
partial="$target.partial"
rm -rf "$partial"
mkdir -p "$partial"
umask 077

log "dumping roles"
compose exec -T postgres pg_dumpall -U "$POSTGRES_USER" --globals-only --no-role-passwords \
  | gzip -6 >"$partial/postgres-globals.sql.gz"

for db in "${PLATFORM_DB:-campushire}" "${KEYCLOAK_DB:-keycloak}" "${OPENFGA_DB:-openfga}"; do
  log "dumping database $db"
  compose exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$db" -Fc -Z 6 >"$partial/$db.dump"
  [[ -s "$partial/$db.dump" ]] || die "dump of $db is empty"
done

if [[ "$with_files" == true ]]; then
  if [[ -n "$(compose ps --status running -q minio 2>/dev/null)" ]]; then
    log "mirroring MinIO bucket ${S3_BUCKET:-campushire-files}"
    mkdir -p "$backup_root/minio-current"
    docker run --rm --network campushire-prod --user "$(id -u):$(id -g)" \
      -e MC_CONFIG_DIR=/tmp/.mc \
      -e "MC_HOST_src=http://${MINIO_ROOT_USER}:${MINIO_ROOT_PASSWORD}@minio:9000" \
      -v "$backup_root/minio-current:/backup" \
      pgsty/mc:RELEASE.2026-10-18T00-00-00Z \
      mirror --overwrite --quiet "src/${S3_BUCKET:-campushire-files}" /backup >/dev/null
    previous="$(find "$backup_root" -mindepth 2 -maxdepth 2 -type d -name minio -path '*/20*T*Z/minio' | sort | tail -1)"
    if command -v rsync >/dev/null 2>&1; then
      rsync -a --delete ${previous:+--link-dest="$previous"} "$backup_root/minio-current/" "$partial/minio/"
    else
      cp -R "$backup_root/minio-current" "$partial/minio"
    fi
  else
    log "minio is not running; skipping files"
  fi
fi

if command -v sha256sum >/dev/null 2>&1; then hasher=(sha256sum); else hasher=(shasum -a 256); fi
sums="$(cd "$partial" && find . -maxdepth 1 -type f -exec "${hasher[@]}" {} +)"
printf '%s\n' "$sums" >"$partial/SHA256SUMS"
echo "image_tag=${IMAGE_TAG:-latest} created=$stamp" >"$partial/MANIFEST"
mv "$partial" "$target"
log "backup written to $target ($(du -sh "$target" | cut -f1))"

find "$backup_root" -mindepth 1 -maxdepth 1 -type d -name '20*T*Z' -mtime "+$retention" -print0 \
  | while IFS= read -r -d '' old; do
      log "removing expired backup $old"
      rm -rf "$old"
    done
