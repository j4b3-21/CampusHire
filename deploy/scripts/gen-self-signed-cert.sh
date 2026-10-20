#!/usr/bin/env bash
# Creates a self-signed TLS certificate for LAN installs without a public domain (browsers will warn once).
#
# Usage: deploy/scripts/gen-self-signed-cert.sh [--force] <domain> [extra-name-or-ip ...]
#   Writes <CERTS_DIR>/fullchain.pem and privkey.pem (CERTS_DIR from deploy/.env, default deploy/certs).
#   Existing certificates are kept unless --force is given. Valid 825 days.
# Example: deploy/scripts/gen-self-signed-cert.sh placements.iiitt.lan 192.168.1.20
set -euo pipefail
source "$(dirname "$0")/lib.sh"

usage() { sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }

force=false
[[ "${1:-}" == "--force" ]] && { force=true; shift; }
[[ $# -ge 1 && "$1" != -* ]] || usage
require_tools openssl

domain="$1"; shift
certs_dir="${CERTS_DIR:-}"
if [[ -z "$certs_dir" && -f "$ENV_FILE" ]]; then
  certs_dir="$(sed -n 's/^CERTS_DIR=//p' "$ENV_FILE" | tail -1)"
fi
certs_dir="${certs_dir:-./certs}"
[[ "$certs_dir" = /* ]] || certs_dir="$DEPLOY_DIR/${certs_dir#./}"
mkdir -p "$certs_dir"

if [[ -s "$certs_dir/fullchain.pem" && -s "$certs_dir/privkey.pem" && "$force" == false ]]; then
  echo "certificate already present in $certs_dir (use --force to replace)"
  exit 0
fi

san="DNS:$domain"
for name in "$@"; do
  if [[ "$name" =~ ^[0-9.]+$ || "$name" == *:* ]]; then san+=",IP:$name"; else san+=",DNS:$name"; fi
done
[[ "$domain" == localhost ]] && san+=",IP:127.0.0.1"

umask 077
openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 825 \
  -keyout "$certs_dir/privkey.pem" -out "$certs_dir/fullchain.pem" \
  -subj "/CN=$domain/O=CampusHire" -addext "subjectAltName=$san" \
  -addext "keyUsage=digitalSignature,keyEncipherment" -addext "extendedKeyUsage=serverAuth" 2>/dev/null
chmod 644 "$certs_dir/fullchain.pem"

echo "self-signed certificate for $san written to $certs_dir"
echo "reload nginx if it is running: docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env exec nginx nginx -s reload"
