#!/usr/bin/env bash
# Samples container CPU/memory and the API connection pools while a load test runs.
#
# Usage: loadtest/monitor.sh <label> [interval-seconds]     (stop with Ctrl-C; runs until killed)
#   Writes loadtest/results/<label>-stats.tsv (docker stats) and <label>-pools.tsv (Hikari active/pending per replica).
#   After the run: loadtest/monitor.sh --report <label>  prints peak CPU/memory per container and the slowest
#   statements from pg_stat_statements (then resets them).
set -euo pipefail

dir="$(cd "$(dirname "$0")" && pwd)"
compose=(docker compose -f "$dir/../deploy/docker-compose.prod.yml" --env-file "$dir/../deploy/.env")
mkdir -p "$dir/results"

if [[ "${1:-}" == --report ]]; then
  label="${2:?label}"
  echo "peak CPU % / memory MiB per container ($label):"
  awk -F'\t' 'NR > 1 { gsub(/%/, "", $3); split($4, m, "/"); mem = m[1];
      if (mem ~ /GiB/) { sub(/GiB/, "", mem); mem *= 1024 } else { sub(/MiB/, "", mem) }
      if ($3 + 0 > cpu[$2]) cpu[$2] = $3 + 0; if (mem + 0 > ram[$2]) ram[$2] = mem + 0 }
      END { for (c in cpu) printf "  %-40s %7.1f %8.0f\n", c, cpu[c], ram[c] }' "$dir/results/$label-stats.tsv" | sort
  echo "pool samples with pending connections: $(awk -F'\t' 'NR > 1 && $4 + 0 > 0' "$dir/results/$label-pools.tsv" 2>/dev/null | wc -l | tr -d ' ')"
  "${compose[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d campushire -P pager=off -q' <<'SQL' || true
SELECT calls, round(mean_exec_time::numeric, 2) AS mean_ms, round(total_exec_time::numeric) AS total_ms,
       left(regexp_replace(query, '\s+', ' ', 'g'), 140) AS query
FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 12;
SELECT pg_stat_statements_reset() IS NOT NULL AS reset;
SQL
  exit 0
fi

label="${1:?usage: loadtest/monitor.sh <label> [interval]}"
interval="${2:-5}"
stats="$dir/results/$label-stats.tsv"
pools="$dir/results/$label-pools.tsv"
printf 'time\tcontainer\tcpu\tmem\n' >"$stats"
printf 'time\treplica\tactive\tpending\n' >"$pools"

metric() {
  docker run --rm --network campushire-prod "${CURL_IMAGE:-curlimages/curl:latest}" -s --max-time 3 \
    "http://$1:8080/actuator/metrics/$2" 2>/dev/null | sed -n 's/.*"value":\([0-9.]*\).*/\1/p'
}

while true; do
  now="$(date +%H:%M:%S)"
  docker stats --no-stream --format '{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}' \
    | grep -E 'campushire-prod|campushire-k6' | sed "s/^/$now\t/" >>"$stats" || true
  for replica in backend-1 backend-2; do
    printf '%s\t%s\t%s\t%s\n' "$now" "$replica" "$(metric "$replica" hikaricp.connections.active)" \
      "$(metric "$replica" hikaricp.connections.pending)" >>"$pools"
  done
  sleep "$interval"
done
