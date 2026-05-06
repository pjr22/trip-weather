#!/usr/bin/env bash
#
# Change the database password for the role the application uses.
#
# Usage:
#   export TRIP_DB_PASSWORD='<current-password>'
#   ./change-db-password.sh '<new-password>'
#
# Optional overrides (defaults match application.properties):
#   TRIP_DB_HOST     (default: localhost)
#   TRIP_DB_PORT     (default: 5432)
#   TRIP_DB_NAME     (default: postgres)
#   TRIP_DB_USERNAME (default: postgres) — the role whose password is changed
#
# What it does, in order:
#   1. Validates inputs (the script needs both the current TRIP_DB_PASSWORD in
#      the environment and the new password as the single argument).
#   2. Issues ALTER USER <role> WITH PASSWORD :'newpw' via psql, using
#      psql's variable interpolation so single quotes / backslashes in the
#      new password are escaped correctly without shell-level quoting tricks.
#   3. Reconnects with the new password and runs SELECT 1 to verify the
#      change actually took (catches typos in special-character passwords
#      that would otherwise leave you locked out).
#   4. Reminds you to update TRIP_DB_PASSWORD wherever it lives (shell rc,
#      systemd unit, container env). The script doesn't try to edit those
#      itself — too invasive.

set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 '<new-password>'" >&2
    echo "       (current password read from TRIP_DB_PASSWORD env var)" >&2
    exit 1
fi

NEW_PASSWORD="$1"
if [[ -z "${NEW_PASSWORD}" ]]; then
    echo "ERROR: new password is empty." >&2
    exit 1
fi

if [[ -z "${TRIP_DB_PASSWORD:-}" ]]; then
    echo "ERROR: TRIP_DB_PASSWORD is not set." >&2
    echo "       export TRIP_DB_PASSWORD='<current-password>' before running this script." >&2
    exit 1
fi

DB_HOST="${TRIP_DB_HOST:-localhost}"
DB_PORT="${TRIP_DB_PORT:-5432}"
DB_NAME="${TRIP_DB_NAME:-postgres}"
DB_USER="${TRIP_DB_USERNAME:-postgres}"

if ! command -v psql >/dev/null 2>&1; then
    echo "ERROR: psql not found on PATH." >&2
    exit 1
fi

echo "Changing password for role '${DB_USER}' on ${DB_HOST}:${DB_PORT}/${DB_NAME}..."

# Step 1: ALTER USER. PGPASSWORD carries the *current* password for this
# connection; --variable hands the new one to psql, which interpolates it
# safely via :'newpw' (auto-quotes the value as a SQL string literal).
PGPASSWORD="${TRIP_DB_PASSWORD}" psql \
    --host="${DB_HOST}" \
    --port="${DB_PORT}" \
    --dbname="${DB_NAME}" \
    --username="${DB_USER}" \
    --no-password \
    --set ON_ERROR_STOP=1 \
    --quiet \
    --variable=role="${DB_USER}" \
    --variable=newpw="${NEW_PASSWORD}" \
    <<'SQL'
ALTER USER :"role" WITH PASSWORD :'newpw';
SQL

# Step 2: verify by reconnecting with the new password. This catches typos
# in special-character passwords that would otherwise leave you locked out.
echo "Verifying new password..."
if ! PGPASSWORD="${NEW_PASSWORD}" psql \
        --host="${DB_HOST}" \
        --port="${DB_PORT}" \
        --dbname="${DB_NAME}" \
        --username="${DB_USER}" \
        --no-password \
        --set ON_ERROR_STOP=1 \
        --quiet \
        --tuples-only \
        --command='SELECT 1;' >/dev/null; then
    echo "ERROR: new password did not authenticate. Database may be in an unexpected state." >&2
    echo "       Check the role's password directly with psql before retrying." >&2
    exit 1
fi

echo ""
echo "Password change verified."
echo ""
echo "Next steps:"
echo "  - Update TRIP_DB_PASSWORD wherever it's set (shell rc, systemd unit,"
echo "    docker env, etc.)."
echo "  - Restart the app so it picks up the new password."
echo ""
echo "  systemd:  sudo systemctl restart tripweather"
echo "  docker:   docker restart tripweather"
