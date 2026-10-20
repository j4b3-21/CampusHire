#!/usr/bin/env bash
# Runs a k6 scenario in the grafana/k6 container: docker run --rm -i --network host grafana/k6 run ...
# Traffic goes through nginx (TLS included). Results: loadtest/results/<scenario>-<UTC stamp>.{summary.txt,json,summary.json}.
#
# Usage: loadtest/run.sh <drive-spike|browse|staff> [extra k6 arguments]
#   BASE_URL     public URL (default: CAMPUSHIRE_PUBLIC_URL or https://CAMPUSHIRE_DOMAIN from deploy/.env)
#   K6_HOST_IP   pin the domain to this IP inside the container (e.g. 127.0.0.1 when k6 runs on the server itself)
#   K6_NETWORK   docker network (default host; campushire-prod with BASE_URL=https://nginx stays on the internal network)
#   K6_IMAGE     default grafana/k6:latest (pin a version for comparable runs); K6_DASHBOARD=1 adds an HTML report
#   Scenario knobs: VUS, RAMP, HOLD, RAMP_DOWN, DURATION, THINK_MIN, THINK_MAX, ITERATIONS, ROWS, INSECURE (true)
# Prerequisite: make seed-load (writes loadtest/data/seed.json). See loadtest/README.md before running.
set -euo pipefail

usage() { sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
[[ $# -ge 1 ]] || usage
scenario="$1"; shift
dir="$(cd "$(dirname "$0")" && pwd)"
[[ -f "$dir/$scenario.js" ]] || usage
[[ -f "$dir/data/seed.json" ]] || { echo "run.sh: $dir/data/seed.json missing; run make seed-load first" >&2; exit 1; }
command -v docker >/dev/null || { echo "run.sh: docker is required" >&2; exit 1; }

env_file="$dir/../deploy/.env"
if [[ -z "${BASE_URL:-}" && -f "$env_file" ]]; then
  domain="$(sed -n 's/^CAMPUSHIRE_DOMAIN=//p' "$env_file" | tail -1)"
  public="$(sed -n 's/^CAMPUSHIRE_PUBLIC_URL=//p' "$env_file" | tail -1)"
  BASE_URL="${public:-${domain:+https://$domain}}"
fi
[[ -n "${BASE_URL:-}" ]] || { echo "run.sh: set BASE_URL or create deploy/.env" >&2; exit 1; }
export BASE_URL

mkdir -p "$dir/results"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
prefix="/results/$scenario-$stamp"

extra=()
if [[ -n "${K6_HOST_IP:-}" ]]; then
  host="${BASE_URL#*://}"; host="${host%%/*}"; host="${host%%:*}"
  extra+=(--add-host "$host:$K6_HOST_IP")
fi
if [[ "${K6_DASHBOARD:-0}" == 1 ]]; then
  extra+=(-e K6_WEB_DASHBOARD=true -e "K6_WEB_DASHBOARD_EXPORT=$prefix.html")
fi
for name in VUS RAMP HOLD RAMP_DOWN DURATION THINK_MIN THINK_MAX ITERATIONS ROWS BASE_URL KEYCLOAK_URL CLIENT_ID INSECURE; do
  [[ -n "${!name:-}" ]] && extra+=(-e "$name=${!name}")
done

echo "k6 $scenario against $BASE_URL -> $dir/results/$scenario-$stamp.*"
exec docker run --rm -i --name "campushire-k6-$scenario" --network "${K6_NETWORK:-host}" \
  --user "$(id -u):$(id -g)" --ulimit nofile=65535:65535 \
  -v "$dir:/loadtest:ro" -v "$dir/results:/results" \
  -e "RESULT_PREFIX=$prefix" "${extra[@]}" \
  "${K6_IMAGE:-grafana/k6:latest}" run --summary-export "$prefix.summary.json" "$@" "/loadtest/$scenario.js"
