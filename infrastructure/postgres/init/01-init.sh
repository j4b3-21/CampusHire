#!/usr/bin/env bash
# Creates one login role and one owned database per service (Keycloak, OpenFGA, CampusHire API).
#
# The postgres image runs /docker-entrypoint-initdb.d scripts ONLY when the data
# volume is empty (first start). Changing these variables later has no effect
# on an existing volume; recreate it with `make infra-reset` (destroys data).
set -euo pipefail

create_db() {
  local db="$1" user="$2" password="$3"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v db="$db" -v db_user="$user" -v db_password="$password" <<'EOSQL'
CREATE ROLE :"db_user" WITH LOGIN PASSWORD :'db_password';
CREATE DATABASE :"db" OWNER :"db_user";
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
EOSQL
}

create_db "$KEYCLOAK_DB" "$KEYCLOAK_DB_USER" "$KEYCLOAK_DB_PASSWORD"
create_db "$OPENFGA_DB" "$OPENFGA_DB_USER" "$OPENFGA_DB_PASSWORD"
create_db "$PLATFORM_DB" "$PLATFORM_DB_USER" "$PLATFORM_DB_PASSWORD"
