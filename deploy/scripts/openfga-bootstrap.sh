#!/usr/bin/env bash
# Creates the OpenFGA store "campushire" and writes the authorization model once, then prints the IDs for deploy/.env.
# The production profile disables in-app bootstrap: with two replicas starting together it could create two stores,
# and every start would write a new model version.
#
# Usage: deploy/scripts/openfga-bootstrap.sh [--write-env | --check]
#   (no option)   find or create the store, reuse the latest model when it equals model.json, else write it; print IDs
#   --write-env   same, then store OPENFGA_STORE_ID / OPENFGA_MODEL_ID in deploy/.env (restart backend-1/2 afterwards)
#   --check       exit 3 when deploy/.env does not point at the store and a model equal to model.json (CI/Jenkins)
# Requires the openfga service to be running (docker compose ... up -d openfga). Tools: docker, jq.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

mode="${1:-print}"
case "$mode" in
  print|--write-env|--check) ;;
  -h|--help) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2 ;;
esac

require_tools docker jq
load_env
model_file="$REPO_DIR/bm4a/platform/src/main/resources/openfga/model.json"
[[ -f "$model_file" ]] || die "$model_file not found"
store_name="campushire"
api="http://openfga:8080"

fga() {
  local method="$1" path="$2"; shift 2
  docker run --rm -i --network campushire-prod "${CURL_IMAGE:-curlimages/curl:latest}" \
    -sS --fail-with-body -X "$method" -H "Authorization: Bearer $OPENFGA_PRESHARED_KEY" \
    -H "Content-Type: application/json" "$@" "$api$path"
}

normalize='walk(if type == "object" then with_entries(select(.value != null and .value != "" and .value != {} and .value != []))
                 else . end) | {schema_version, type_definitions: (.type_definitions | sort_by(.type)), conditions}'

fga GET /healthz >/dev/null 2>&1 || die "OpenFGA is not reachable on network campushire-prod (start the stack first)"

store_id="$(fga GET "/stores?page_size=100" | jq -r --arg n "$store_name" '[.stores[] | select(.name == $n)][0].id // empty')"
wanted="$(jq -cS "$normalize" "$model_file")"

if [[ "$mode" == --check ]]; then
  [[ -n "$store_id" && "$store_id" == "${OPENFGA_STORE_ID:-}" ]] \
    || { echo "OPENFGA_STORE_ID does not match store '$store_name' (${store_id:-missing})" >&2; exit 3; }
  current="$(fga GET "/stores/$store_id/authorization-models/${OPENFGA_MODEL_ID:-none}" 2>/dev/null \
    | jq -cS ".authorization_model | $normalize" 2>/dev/null || true)"
  [[ "$current" == "$wanted" ]] \
    || { echo "OPENFGA_MODEL_ID does not match model.json; run deploy/scripts/openfga-bootstrap.sh --write-env" >&2; exit 3; }
  echo "OpenFGA store $store_id and model ${OPENFGA_MODEL_ID} are up to date"
  exit 0
fi

if [[ -z "$store_id" ]]; then
  store_id="$(fga POST /stores -d "{\"name\":\"$store_name\"}" | jq -er .id)"
  log "created OpenFGA store '$store_name' ($store_id)"
fi

latest="$(fga GET "/stores/$store_id/authorization-models?page_size=1" | jq -c '.authorization_models[0] // empty')"
model_id=""
if [[ -n "$latest" && "$(jq -cS "$normalize" <<<"$latest")" == "$wanted" ]]; then
  model_id="$(jq -r .id <<<"$latest")"
  log "latest model $model_id already equals model.json"
else
  model_id="$(fga POST "/stores/$store_id/authorization-models" --data-binary @- <"$model_file" | jq -er .authorization_model_id)"
  log "wrote authorization model $model_id"
fi

echo "OPENFGA_STORE_ID=$store_id"
echo "OPENFGA_MODEL_ID=$model_id"

if [[ "$mode" == --write-env ]]; then
  tmp="$(mktemp "$ENV_FILE.XXXXXX")"
  awk -v s="$store_id" -v m="$model_id" '
    /^OPENFGA_STORE_ID=/ { print "OPENFGA_STORE_ID=" s; fs = 1; next }
    /^OPENFGA_MODEL_ID=/ { print "OPENFGA_MODEL_ID=" m; fm = 1; next }
    { print }
    END { if (!fs) print "OPENFGA_STORE_ID=" s; if (!fm) print "OPENFGA_MODEL_ID=" m }' "$ENV_FILE" >"$tmp"
  chmod 600 "$tmp"
  mv "$tmp" "$ENV_FILE"
  log "updated $ENV_FILE; apply with: docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d"
fi
