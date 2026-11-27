#!/bin/sh
# First initialization of a NEW production-template volume only. Never resets data.
set -eu
: "${DB_USER:?Set application database role}"
: "${DB_PASSWORD:?Set application database password}"
[ "$DB_USER" != "$POSTGRES_USER" ] || { echo 'Application and bootstrap roles must differ' >&2; exit 1; }
[ "${#DB_PASSWORD}" -ge 24 ] || { echo 'Application database password is too short' >&2; exit 1; }
[ "${#POSTGRES_PASSWORD}" -ge 24 ] || { echo 'Bootstrap database password is too short' >&2; exit 1; }
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\getenv app_user DB_USER
\getenv app_password DB_PASSWORD
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION', :'app_user', :'app_password') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'app_user') \gexec
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SELECT format('ALTER SCHEMA public OWNER TO %I', :'app_user') \gexec
SQL
