#!/usr/bin/env bash
#
# Combined database migration for the user-accounts feature
# (USER_ACCOUNTS_PLAN.md). Applies in a single transaction:
#
#   * users.email / users.password_hash / users.enabled (+ guest-row backfill,
#     UNIQUE constraint on email)
#   * email_verifications, password_resets tables
#   * ON DELETE CASCADE on waypoints.route_id and routes.user_id
#   * persistent_logins (Spring Security remember-me) + username index
#
# The script connects to the database directly via psql (it does NOT use
# Spring's TRIP_JPA_DDL=update path). Stop the app before running it.
#
# Usage:
#   export TRIP_DB_PASSWORD='<password>'
#   ./user-accounts-db-migration.sh
#
# Optional overrides (defaults match application.properties):
#   TRIP_DB_HOST     (default: localhost)
#   TRIP_DB_PORT     (default: 5432)
#   TRIP_DB_NAME     (default: postgres)
#   TRIP_DB_USERNAME (default: postgres)
#
# The script is idempotent: re-running it on an already-migrated database
# performs the validation checks and exits cleanly. The whole change runs in
# a single transaction — any failure rolls back to the original schema.

set -euo pipefail

if [[ -z "${TRIP_DB_PASSWORD:-}" ]]; then
    echo "ERROR: TRIP_DB_PASSWORD is not set." >&2
    echo "       export TRIP_DB_PASSWORD='<password>' before running this script." >&2
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

echo "Migrating ${DB_NAME} on ${DB_HOST}:${DB_PORT} as ${DB_USER}..."

# PGPASSWORD is consumed by psql; everything else uses standard libpq env names
# to avoid leaking the password to the process command line.
export PGPASSWORD="${TRIP_DB_PASSWORD}"

psql \
    --host="${DB_HOST}" \
    --port="${DB_PORT}" \
    --dbname="${DB_NAME}" \
    --username="${DB_USER}" \
    --no-password \
    --set ON_ERROR_STOP=1 \
    --quiet \
    <<'SQL'
\echo 'Starting user-accounts migration (single transaction)...'

BEGIN;

-- ============================================================================
-- 1. users table — new columns for authentication
-- ============================================================================

ALTER TABLE users ADD COLUMN IF NOT EXISTS email         VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);
ALTER TABLE users ADD COLUMN IF NOT EXISTS enabled       BOOLEAN;

-- Backfill the shared guest user. The plan reserves 'guest@local' as a
-- non-routable sentinel; guest has no password_hash and so can never log in.
UPDATE users SET email   = 'guest@local' WHERE name = 'guest' AND email   IS NULL;
UPDATE users SET enabled = TRUE          WHERE name = 'guest' AND enabled IS NULL;

-- Sanity-check before tightening NOT NULL: any rows still missing values
-- indicate a non-guest user we don't know how to backfill. Abort cleanly.
DO $$
DECLARE missing_count INT;
BEGIN
    SELECT COUNT(*) INTO missing_count
      FROM users
     WHERE email IS NULL OR enabled IS NULL;
    IF missing_count > 0 THEN
        RAISE EXCEPTION 'Aborting: % user row(s) still have NULL email or enabled. '
                        'Backfill them manually before re-running.', missing_count;
    END IF;
END $$;

-- Tighten NOT NULL on the new columns. SET NOT NULL is idempotent.
ALTER TABLE users ALTER COLUMN email   SET NOT NULL;
ALTER TABLE users ALTER COLUMN enabled SET NOT NULL;

-- Add UNIQUE on users.email if not already present.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'users'::regclass
           AND contype  = 'u'
           AND conkey   = ARRAY[(SELECT attnum FROM pg_attribute
                                  WHERE attrelid = 'users'::regclass AND attname = 'email')]
    ) THEN
        ALTER TABLE users ADD CONSTRAINT users_email_key UNIQUE (email);
        RAISE NOTICE 'Added UNIQUE constraint users_email_key.';
    ELSE
        RAISE NOTICE 'UNIQUE constraint on users.email already exists; skipping.';
    END IF;
END $$;

-- ============================================================================
-- 2. New tables for v1 self-service flows
-- ============================================================================

-- CREATE TABLE IF NOT EXISTS makes re-runs safe; the FKs use ON DELETE CASCADE
-- so account deletion sweeps any outstanding tokens.
CREATE TABLE IF NOT EXISTS email_verifications (
    id          UUID PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    created     TIMESTAMP    NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    consumed_at TIMESTAMP    NULL
);

CREATE TABLE IF NOT EXISTS password_resets (
    id          UUID PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    created     TIMESTAMP    NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    consumed_at TIMESTAMP    NULL
);

-- ============================================================================
-- 3. ON DELETE CASCADE on existing FKs so account deletion sweeps cleanly
-- ============================================================================

-- waypoints.route_id → routes.id. Hibernate may have generated a different
-- name (FK<hash> vs. waypoints_route_id_fkey), so we look it up dynamically.
-- confdeltype = 'c' means CASCADE is already in place — no work needed.
DO $$
DECLARE
    existing_fk    TEXT;
    existing_action CHAR;
BEGIN
    SELECT con.conname, con.confdeltype
      INTO existing_fk, existing_action
      FROM pg_constraint con
      JOIN pg_class rel ON rel.oid = con.conrelid
     WHERE rel.relname = 'waypoints'
       AND con.contype = 'f'
       AND con.confrelid = 'routes'::regclass
     LIMIT 1;

    IF existing_fk IS NOT NULL AND existing_action = 'c' THEN
        RAISE NOTICE 'waypoints -> routes FK already has ON DELETE CASCADE; skipping.';
    ELSE
        IF existing_fk IS NOT NULL THEN
            EXECUTE format('ALTER TABLE waypoints DROP CONSTRAINT %I', existing_fk);
        END IF;
        ALTER TABLE waypoints
            ADD CONSTRAINT waypoints_route_id_fkey
            FOREIGN KEY (route_id) REFERENCES routes(id) ON DELETE CASCADE;
        RAISE NOTICE 'waypoints -> routes FK recreated with ON DELETE CASCADE.';
    END IF;
END $$;

-- routes.user_id → users.id. Symmetry with waypoints (per plan §5.5) so
-- deleting a user sweeps their routes (and transitively their waypoints).
DO $$
DECLARE
    existing_fk    TEXT;
    existing_action CHAR;
BEGIN
    SELECT con.conname, con.confdeltype
      INTO existing_fk, existing_action
      FROM pg_constraint con
      JOIN pg_class rel ON rel.oid = con.conrelid
     WHERE rel.relname = 'routes'
       AND con.contype = 'f'
       AND con.confrelid = 'users'::regclass
     LIMIT 1;

    IF existing_fk IS NOT NULL AND existing_action = 'c' THEN
        RAISE NOTICE 'routes -> users FK already has ON DELETE CASCADE; skipping.';
    ELSE
        IF existing_fk IS NOT NULL THEN
            EXECUTE format('ALTER TABLE routes DROP CONSTRAINT %I', existing_fk);
        END IF;
        ALTER TABLE routes
            ADD CONSTRAINT routes_user_id_fkey
            FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
        RAISE NOTICE 'routes -> users FK recreated with ON DELETE CASCADE.';
    END IF;
END $$;

-- ============================================================================
-- 4. persistent_logins — Spring Security remember-me
-- ============================================================================

-- Spring Security's JdbcTokenRepositoryImpl owns the layout of this table.
-- See org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl
-- (CREATE_TABLE_QUERY constant). No JPA entity in our codebase — Spring writes/
-- reads it directly. username matches UserDetails#username, which we set to
-- the user's email in TripUserDetailsService; 255 chars accommodates that.
CREATE TABLE IF NOT EXISTS persistent_logins (
    username  VARCHAR(255) NOT NULL,
    series    VARCHAR(64)  PRIMARY KEY,
    token     VARCHAR(64)  NOT NULL,
    last_used TIMESTAMP    NOT NULL
);

-- Lookups happen by username during revocation (change/reset/delete-account)
-- and by series on every authenticated request, but series is the PK so it's
-- already covered. Adding an index on username so removeUserTokens() doesn't
-- table-scan once the table grows.
CREATE INDEX IF NOT EXISTS persistent_logins_username_idx
    ON persistent_logins (username);

COMMIT;

-- ============================================================================
-- 5. Verification — anything missing here means the migration didn't take
-- ============================================================================

\echo ''
\echo 'Verification:'
\echo '-------------'

\echo ''
\echo '-- users columns --'
SELECT
    column_name,
    data_type,
    is_nullable,
    character_maximum_length AS max_len
  FROM information_schema.columns
 WHERE table_schema = current_schema()
   AND table_name   = 'users'
   AND column_name IN ('email', 'password_hash', 'enabled')
 ORDER BY column_name;

\echo ''
\echo '-- new tables --'
SELECT table_name
  FROM information_schema.tables
 WHERE table_schema = current_schema()
   AND table_name IN ('email_verifications', 'password_resets', 'persistent_logins')
 ORDER BY table_name;

\echo ''
\echo '-- FK ON DELETE behaviour --'
SELECT con.conname,
       rel.relname AS table_name,
       CASE con.confdeltype
           WHEN 'c' THEN 'CASCADE'
           WHEN 'r' THEN 'RESTRICT'
           WHEN 'a' THEN 'NO ACTION'
           WHEN 'n' THEN 'SET NULL'
           WHEN 'd' THEN 'SET DEFAULT'
           ELSE con.confdeltype::TEXT
       END AS on_delete
  FROM pg_constraint con
  JOIN pg_class rel ON rel.oid = con.conrelid
 WHERE con.contype = 'f'
   AND ((rel.relname = 'waypoints' AND con.confrelid = 'routes'::regclass)
     OR (rel.relname = 'routes'    AND con.confrelid = 'users'::regclass))
 ORDER BY rel.relname;

\echo ''
\echo '-- persistent_logins indexes --'
SELECT indexname
  FROM pg_indexes
 WHERE schemaname = current_schema()
   AND tablename  = 'persistent_logins'
 ORDER BY indexname;

\echo ''
\echo 'User-accounts migration complete.'
SQL

echo ""
echo "Done. Restart the app with TRIP_JPA_DDL unset (or 'validate') to confirm"
echo "the schema matches the entities."
