#!/bin/sh
# Least-privilege roles for RaceLab (runs once, on an empty data directory).
#  - POSTGRES_USER (racelab_owner): owns the schema, used only by Flyway migrations.
#  - racelab_app: runtime role of the backend — DML only (grants applied by migration V3).
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
CREATE ROLE racelab_app LOGIN PASSWORD '${RACELAB_APP_PASSWORD}' CONNECTION LIMIT 150;
GRANT CONNECT ON DATABASE "${POSTGRES_DB}" TO racelab_app;
GRANT USAGE ON SCHEMA public TO racelab_app;
-- safety net: no transaction may stay idle forever, even if a client disappears
ALTER ROLE racelab_app SET idle_in_transaction_session_timeout = '15min';
ALTER ROLE racelab_app SET statement_timeout = '2min';
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SQL
