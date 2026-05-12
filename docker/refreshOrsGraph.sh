#!/bin/bash
#
# Phase 2b cron-tick worker for OSM-pbf lifecycle. See ADMIN_CONSOLE.md.
#
# Reads the pbf_files table and, per active row:
#   1. Cheap upstream md5 check, only when next_check_at is due — keeps
#      last_remote_md5 / last_remote_modified current so the admin UI
#      shows freshness.
#   2. Stale-apply recovery — if a prior apply set last_apply_started_at
#      but never set last_apply_finished_at and the marker is older than
#      4 hours, clear it so the row is no longer flagged as in-flight.
#   3. Full apply, only when next_update_at is due — fetches the .md5
#      first and SKIPS the heavy work (status = NO_CHANGE) when the md5
#      matches last_apply_md5. Otherwise: download, verify, stop trip-ors,
#      atomically swap the pbf, wipe the graph volume, restart, and refresh
#      the coverage polygon for the single routing_coverage row whose name
#      equals pbf_name (Phase 2c: routing_coverage is 1:1 with pbf_files).
#   4. Auto-reschedule next_update_at after a successful apply, but ONLY
#      if update_interval_days is set (NULL = admin schedules each apply).
#
# Single-loaded-pbf invariant: runOrs.sh / ors-config.yml mount and load
# exactly one .osm.pbf at a time. After every successful apply (or
# NO_CHANGE), enforce_single_loaded_pbf clears geom + fetched_at for every
# OTHER routing_coverage row so the dispatcher's coversAll() (which
# filters `enabled AND geom IS NOT NULL`) sees local coverage only for
# the actually-loaded pbf. ADMIN_CONSOLE.md Phase 2c addendum has the
# detail; this is the right design until the merge-multiple-pbfs follow-up
# lands.
#
# Host crontab — every minute, flock-guarded so two ticks can't overlap:
#
#   * * * * *  /opt/trip-weather/docker/refreshOrsGraph.sh \
#                >> /var/log/trip-pbf-cron.log 2>&1
#
# Manual invocation flags (see also --help):
#
#   --force, -f  Bypass next_check_at and run the cheap upstream md5
#                check for every active pbf_files row. Does NOT bypass
#                the apply gate; the apply still requires
#                next_update_at to be set and due.
#
# Required env vars:
#
#   TRIP_DB_PASSWORD          Password for the postgres user.
#   TRIP_ADMIN_REFRESH_TOKEN  Same value the trip-weather container sees;
#                             used to authenticate the coverage-refresh
#                             POSTs at the end of a successful apply.
#
# Optional env vars (defaults match application.properties):
#
#   TRIP_DB_HOST              default 'localhost'
#   TRIP_DB_PORT              default 5432
#   TRIP_DB_NAME              default 'postgres'
#   TRIP_DB_USERNAME          default 'postgres'
#   TRIP_LOCAL_APP_BASE_URL   default 'http://localhost:8090' (bootRun
#                             port per CLAUDE.md). Production cron host
#                             should set this to the nginx proxy port,
#                             typically 'http://localhost:8091', which
#                             reverse-proxies /api/admin/refresh-coverage/*
#                             into the trip-weather container. Distinct
#                             from TRIP_APP_BASE_URL (which is the *app's*
#                             public self-URL used for outbound email
#                             links and is set on the trip-weather
#                             container, not on the cron host).
#
#   APP_BASE_URL              Deprecated alias for TRIP_LOCAL_APP_BASE_URL.
#                             Still honored as a fallback so older cron
#                             environments don't break; prefer the
#                             TRIP_LOCAL_APP_BASE_URL name going forward.
#
# IMPORTANT: TRIP_ADMIN_REFRESH_TOKEN must be set to the *same value* the
# trip-weather application sees as trip.admin.refresh-token, otherwise
# every coverage-refresh POST will return 401. When trip.admin.refresh-token
# is blank inside the app, XAdminTokenAuthenticationFilter fail-closes and
# rejects every X-Admin-Token header regardless of what it contains.

set -euo pipefail

# ---- Argument parsing ------------------------------------------------------
#
# Only one optional flag for now: --force / -f, which runs the cheap
# upstream md5 check on every active pbf_files row regardless of
# next_check_at. Useful when the operator just wants to see whether a
# new pbf is available without waiting up to a week for the next
# scheduled check (or twiddling next_check_at via psql).
#
# --force deliberately does NOT bypass the apply gate (next_update_at).
# The apply is the heavy operation (multi-GB download, container restart,
# graph rebuild) and we want it admin-driven. Use the admin Pbfs view's
# "Schedule now" button to fire an apply.

FORCE_CHECK=false

usage() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Options:
  --force, -f    Run the cheap upstream md5 check for every active
                 pbf_files row regardless of its next_check_at schedule.
                 Does not bypass the apply gate (next_update_at); use
                 the admin Pbfs view's "Schedule now" to trigger one.
  --help, -h     Show this help and exit.
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --force|-f)
            FORCE_CHECK=true
            shift
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            echo "ERROR: unknown argument: $1" >&2
            usage >&2
            exit 1
            ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA_DIR="${SCRIPT_DIR}/ors-data"

# Show curl progress bars only when stderr is an interactive terminal —
# avoids dumping a long "##############" line into the cron log on every
# pbf download.
if [ -t 2 ]; then
    CURL_PROGRESS_OPT="--progress-bar"
else
    CURL_PROGRESS_OPT="-s"
fi

# Stale-apply recovery window. Matches APPLY_STUCK_AFTER_HOURS in PbfFileDto.java.
APPLY_STALE_AFTER_HOURS="${APPLY_STALE_AFTER_HOURS:-4}"

DB_HOST="${TRIP_DB_HOST:-localhost}"
DB_PORT="${TRIP_DB_PORT:-5432}"
DB_NAME="${TRIP_DB_NAME:-postgres}"
DB_USER="${TRIP_DB_USERNAME:-postgres}"
# URL where the cron can reach the trip-weather admin API from this host.
# Production: typically http://localhost:8091 (the nginx reverse proxy).
# Dev (bootRun): http://localhost:8090 (the default below).
# Distinct from TRIP_APP_BASE_URL which is the app's *public* self-URL
# (used by trip-weather for outbound email links), set inside the
# trip-weather container, not here.
#
# APP_BASE_URL (no TRIP_ prefix) is honored as a deprecated fallback so
# operators who set it on the pre-rename script don't have to chase
# their cron environment immediately.
TRIP_LOCAL_APP_BASE_URL="${TRIP_LOCAL_APP_BASE_URL:-${APP_BASE_URL:-http://localhost:8090}}"

if [ -z "${TRIP_DB_PASSWORD:-}" ]; then
    echo "ERROR: TRIP_DB_PASSWORD is not set." >&2
    exit 1
fi
if [ -z "${TRIP_ADMIN_REFRESH_TOKEN:-}" ]; then
    echo "ERROR: TRIP_ADMIN_REFRESH_TOKEN is not set." >&2
    exit 1
fi

# ----------------------------------------------------------------------------
# Lock-file selection + acquisition.
#
# Production Linux convention is /var/lock/; that path doesn't exist on Git
# Bash on Windows or some macOS setups. Fall back to ${TMPDIR:-/tmp} when
# /var/lock isn't writable. If flock isn't available at all (e.g. minimal
# Git Bash without util-linux), proceed without overlap protection — the
# per-row state machine in pbf_files (last_apply_started_at + the 4 h
# stale-detection window) still prevents double applies, just less tightly
# than a host-level flock would.
# ----------------------------------------------------------------------------

LOCK_FILE="${TRIP_PBF_CRON_LOCK:-}"
if [ -z "${LOCK_FILE}" ]; then
    if [ -d /var/lock ] && [ -w /var/lock ]; then
        LOCK_FILE=/var/lock/trip-pbf-cron.lock
    else
        LOCK_FILE="${TMPDIR:-/tmp}/trip-pbf-cron.lock"
    fi
fi

acquire_lock_or_proceed() {
    if ! command -v flock >/dev/null 2>&1; then
        echo "[$(date -Iseconds)] WARN: flock not available; running without overlap protection" >&2
        return 0
    fi
    if ! exec 9>"${LOCK_FILE}" 2>/dev/null; then
        echo "[$(date -Iseconds)] WARN: could not open lock file ${LOCK_FILE}; running without overlap protection" >&2
        return 0
    fi
    if ! flock -n 9; then
        # Lock held by another tick — normal mid-apply state. Exit silently
        # so cron doesn't spam logs every minute.
        exit 0
    fi
    return 0
}

acquire_lock_or_proceed

mkdir -p "${DATA_DIR}"
export PGPASSWORD="${TRIP_DB_PASSWORD}"

# psql wrapper. -A unaligned, -t tuples only, -F '|' field separator. The
# resulting output is easy to parse with `while IFS='|' read -r`.
psql_query() {
    psql \
        --host="${DB_HOST}" \
        --port="${DB_PORT}" \
        --dbname="${DB_NAME}" \
        --username="${DB_USER}" \
        --no-password \
        --set ON_ERROR_STOP=1 \
        --quiet -A -t -F '|' \
        -c "$1"
}

psql_exec() {
    psql \
        --host="${DB_HOST}" \
        --port="${DB_PORT}" \
        --dbname="${DB_NAME}" \
        --username="${DB_USER}" \
        --no-password \
        --set ON_ERROR_STOP=1 \
        --quiet \
        -c "$1" >/dev/null
}

# ---- Step 1: cheap md5 check for rows whose next_check_at is due. -----------
# next_check_at NULL is treated as "do it now" — typical for freshly added rows.
do_cheap_check() {
    local pbf_name="$1"
    local geofabrik_url="$2"
    local check_interval_days="$3"

    local md5_url="${geofabrik_url}.md5"
    local md5_body
    if ! md5_body="$(curl -fsSL --max-time 30 "${md5_url}" 2>/dev/null)"; then
        echo "  [${pbf_name}] WARN: md5 fetch failed for ${md5_url}; will retry next tick"
        return 1
    fi
    local md5_hex
    md5_hex="$(echo "${md5_body}" | awk '{print $1; exit}')"
    if ! [[ "${md5_hex}" =~ ^[0-9a-fA-F]{32}$ ]]; then
        echo "  [${pbf_name}] WARN: response from ${md5_url} didn't start with 32-char md5"
        return 1
    fi

    # Last-Modified — best-effort, separate HEAD so we don't have to keep
    # parsing curl headers from the GET above.
    local last_modified
    last_modified="$(curl -fsSI --max-time 30 "${md5_url}" 2>/dev/null \
        | awk 'BEGIN{IGNORECASE=1} /^last-modified:/ { sub(/^[^:]*: */,""); sub(/\r$/,""); print; exit }')"

    local lm_sql="NULL"
    if [ -n "${last_modified}" ]; then
        lm_sql="'${last_modified}'::timestamptz"
    fi

    psql_exec "UPDATE pbf_files SET
        last_check_at = now(),
        last_remote_md5 = lower('${md5_hex}'),
        last_remote_modified = ${lm_sql},
        next_check_at = now() + INTERVAL '${check_interval_days} days'
        WHERE pbf_name = '${pbf_name}'"
    echo "  [${pbf_name}] cheap check: remote md5 = ${md5_hex}"
    return 0
}

# ---- Step 2: stale-apply recovery. ------------------------------------------
do_stale_apply_recovery() {
    # Single SQL statement — clear last_apply_started_at on any row that's
    # been "in flight" longer than the threshold. Logs the names cleared.
    local cleared
    cleared="$(psql_query "
        WITH cleared AS (
            UPDATE pbf_files
               SET last_apply_started_at = NULL
             WHERE last_apply_started_at IS NOT NULL
               AND last_apply_finished_at IS NULL
               AND last_apply_started_at < now() - INTERVAL '${APPLY_STALE_AFTER_HOURS} hours'
            RETURNING pbf_name
        )
        SELECT string_agg(pbf_name, ', ') FROM cleared")"
    if [ -n "${cleared}" ] && [ "${cleared}" != " " ]; then
        echo "  stale-apply recovery: cleared in-flight marker on: ${cleared}"
    fi
}

# ---- Step 3: full apply for rows whose next_update_at is due. ---------------
do_apply() {
    local pbf_name="$1"
    local geofabrik_url="$2"
    local update_interval_days="$3"
    local last_apply_md5="$4"
    local check_interval_days="$5"

    echo "  [${pbf_name}] apply: fetching upstream .md5 to decide if rebuild is needed"

    psql_exec "UPDATE pbf_files SET last_apply_started_at = now(),
        last_apply_finished_at = NULL,
        last_apply_error = NULL
        WHERE pbf_name = '${pbf_name}'"

    local md5_url="${geofabrik_url}.md5"
    local md5_body
    if ! md5_body="$(curl -fsSL --max-time 30 "${md5_url}" 2>/dev/null)"; then
        record_apply_failure "${pbf_name}" "CHECK_FAILED" "could not fetch ${md5_url}"
        return 1
    fi
    local remote_md5
    remote_md5="$(echo "${md5_body}" | awk '{print $1; exit}')"
    if ! [[ "${remote_md5}" =~ ^[0-9a-fA-F]{32}$ ]]; then
        record_apply_failure "${pbf_name}" "CHECK_FAILED" "malformed md5 response from ${md5_url}"
        return 1
    fi
    remote_md5="$(echo "${remote_md5}" | tr 'A-F' 'a-f')"

    # Short-circuit: upstream md5 matches what's deployed. No rebuild needed.
    # Polygon staleness self-heal happens in the main per-row loop now (see
    # check_and_refresh_stale_polygons), independent of the apply gate, so
    # the NO_CHANGE branch just records status and exits.
    if [ "${remote_md5}" = "${last_apply_md5}" ] && [ -n "${last_apply_md5}" ]; then
        echo "  [${pbf_name}] apply: NO_CHANGE — deployed md5 matches upstream"
        record_apply_no_change "${pbf_name}" "${remote_md5}" "${update_interval_days}" "${check_interval_days}"
        return 0
    fi

    # Heavy work begins. Local pbf path matches "<pbf_name>-latest.osm.pbf"
    # by Geofabrik convention; if your deployment uses a non-standard name,
    # override via the row's geofabrik_url and adjust DATA_DIR layout.
    local pbf_basename
    pbf_basename="$(basename "${geofabrik_url}")"
    local pbf_path="${DATA_DIR}/${pbf_basename}"
    local pbf_tmp="${pbf_path}.new"
    local md5_path="${pbf_path}.md5"

    echo "  [${pbf_name}] apply: target path ${pbf_path}"
    echo "  [${pbf_name}] apply: downloading ${geofabrik_url}"
    # Progress bar goes to stderr when the script is run interactively;
    # silent in cron (see CURL_PROGRESS_OPT init at the top of the script).
    if ! curl -fL --max-time 7200 ${CURL_PROGRESS_OPT} -o "${pbf_tmp}" "${geofabrik_url}"; then
        rm -f "${pbf_tmp}"
        record_apply_failure "${pbf_name}" "DOWNLOAD_FAILED" "curl failed downloading ${geofabrik_url}"
        return 1
    fi

    local actual_md5
    actual_md5="$(md5sum "${pbf_tmp}" | awk '{print $1}')"
    if [ "${remote_md5}" != "${actual_md5}" ]; then
        rm -f "${pbf_tmp}"
        record_apply_failure "${pbf_name}" "DOWNLOAD_FAILED" \
            "md5 mismatch: expected ${remote_md5}, got ${actual_md5}"
        return 1
    fi
    echo "  [${pbf_name}] apply: md5 verified ${actual_md5}"

    echo "  [${pbf_name}] apply: stopping trip-ors and swapping pbf into ${pbf_path}"
    docker stop trip-ors >/dev/null 2>&1 || true
    docker rm   trip-ors >/dev/null 2>&1 || true
    mv -f "${pbf_tmp}" "${pbf_path}"
    echo "${remote_md5}  ${pbf_basename}" > "${md5_path}"

    if ! docker volume rm trip_ors_graph >/dev/null 2>&1; then
        # The volume might not exist if trip-ors has never been built;
        # not fatal — runOrs.sh will create it.
        echo "  [${pbf_name}] apply: trip_ors_graph volume didn't exist (continuing)"
    fi

    echo "  [${pbf_name}] apply: restarting trip-ors (graph rebuild starts; ~30-60 min for Colorado)"
    if ! ORS_PBF_FILE="${pbf_basename}" "${SCRIPT_DIR}/runOrs.sh" >/dev/null; then
        record_apply_failure "${pbf_name}" "RESTART_FAILED" "runOrs.sh failed"
        return 1
    fi

    # Polygon refresh: Phase 2c collapsed routing_coverage to 1:1 with
    # pbf_files (routing_coverage.name == pbf_files.pbf_name), so we either
    # have exactly one row to refresh or none (the row should be auto-
    # created when admin adds the pbf; absence means the migration didn't
    # run or somebody manually deleted the row).
    local has_row
    has_row="$(psql_query "SELECT 1 FROM routing_coverage WHERE name = '${pbf_name}'")"
    if [ -n "${has_row}" ] && [ "${has_row}" != " " ]; then
        refresh_polygons_for_regions "${pbf_name}" "${pbf_name}"
    else
        echo "  [${pbf_name}] apply: no routing_coverage row for this pbf; skipping polygon refresh"
    fi

    record_apply_success "${pbf_name}" "${remote_md5}" "${update_interval_days}" "${check_interval_days}"
    echo "  [${pbf_name}] apply: SUCCESS"
}

# Polygon staleness self-heal — runs every tick for every active pbf,
# independent of the apply gate. If the currently-loaded pbf's polygon is
# missing or older than its last_apply_finished_at, the previous apply's
# polygon-refresh step didn't reach the row (transient network, prior
# column-name bug, manual re-seed, etc.) and we refresh it.
#
# Important: with the single-loaded-pbf invariant (see
# enforce_single_loaded_pbf), only ONE pbf is actually loaded into
# trip-ors at a time. Re-fetching polygons for other pbfs would resurrect
# stale-but-no-longer-valid local coverage — the dispatcher would then
# route into a polygon whose underlying graph isn't loaded and fail the
# local call before falling back. So self-heal runs only when this row IS
# the currently-loaded pbf (the most recent successful apply).
check_and_refresh_stale_polygons() {
    local pbf_name="$1"
    # Most recent successful (OK or NO_CHANGE) apply across all rows is
    # the currently-loaded pbf. If this row isn't it, leave it alone —
    # its geom should be NULL anyway (enforced after the other pbf's
    # successful apply) and re-fetching it would break the invariant.
    local loaded_pbf
    loaded_pbf="$(psql_query "SELECT pbf_name FROM pbf_files
        WHERE last_apply_finished_at IS NOT NULL
          AND last_apply_status IN ('OK', 'NO_CHANGE')
        ORDER BY last_apply_finished_at DESC
        LIMIT 1")"
    loaded_pbf="$(echo "${loaded_pbf}" | tr -d '[:space:]')"

    if [ "${loaded_pbf}" != "${pbf_name}" ]; then
        # Either this row was never applied (skip silently) or it's an
        # older apply that another pbf has since replaced (the
        # enforce_single_loaded_pbf step cleared its polygon).
        return 0
    fi

    local stale_regions
    stale_regions="$(psql_query "SELECT string_agg(rc.name, ',')
        FROM routing_coverage rc
        JOIN pbf_files pf ON pf.pbf_name = rc.name
        WHERE rc.name = '${pbf_name}'
          AND pf.last_apply_finished_at IS NOT NULL
          AND (rc.fetched_at IS NULL
               OR rc.fetched_at < pf.last_apply_finished_at)")"
    if [ -n "${stale_regions}" ] && [ "${stale_regions}" != " " ]; then
        echo "    polygons stale relative to last apply: ${stale_regions}"
        refresh_polygons_for_regions "${pbf_name}" "${stale_regions}"
    else
        echo "    polygons in sync with last apply"
    fi
}

# Refresh routing_coverage polygons for one or more comma-separated region
# names. POSTs to the JVM endpoint per region — the
# GeofabrikCoverageLoader.refresh path fetches the .poly, parses it,
# upserts the row (advancing routing_coverage.fetched_at), and records
# an ors-coverage:{region} loader_runs entry. Used by both the full-apply
# (post-swap) path and the standalone staleness self-heal path.
refresh_polygons_for_regions() {
    local pbf_name="$1"
    local regions="$2"
    for region in ${regions//,/ }; do
        local http_code
        # || true so a failed curl (e.g. connection refused) doesn't trip
        # set -e and abort the loop before the remaining regions get
        # attempted. curl's stderr still surfaces the underlying message
        # (-sS keeps errors visible even with -s).
        http_code="$(curl -sS -o /tmp/coverage-refresh.out -w "%{http_code}" \
            -X POST \
            -H "X-Admin-Token: ${TRIP_ADMIN_REFRESH_TOKEN}" \
            "${TRIP_LOCAL_APP_BASE_URL}/api/admin/refresh-coverage/${region}")" || true
        # When curl errors out before getting a response (DNS, connect),
        # the substitution produces an empty string. Normalise so the
        # comparison + log have a usable value.
        http_code="${http_code:-000}"
        if [ "${http_code}" != "200" ]; then
            local body
            body="$(cat /tmp/coverage-refresh.out 2>/dev/null | tr '\n' ' ' | head -c 200)"
            case "${http_code}" in
                000)
                    echo "  [${pbf_name}] WARN [${region}]: could not reach ${TRIP_LOCAL_APP_BASE_URL} (is trip-weather running? override TRIP_LOCAL_APP_BASE_URL if not on port 8090)"
                    ;;
                401)
                    echo "  [${pbf_name}] WARN [${region}]: HTTP 401 from ${TRIP_LOCAL_APP_BASE_URL} — X-Admin-Token rejected. Check that TRIP_ADMIN_REFRESH_TOKEN is set in BOTH the trip-weather environment AND this script's environment, and that they match."
                    ;;
                404)
                    echo "  [${pbf_name}] WARN [${region}]: HTTP 404 from ${TRIP_LOCAL_APP_BASE_URL} — no pbf_files row named '${region}', or TRIP_LOCAL_APP_BASE_URL is wrong (nginx not routing /api/admin/* through? running on port 8091 in prod)"
                    ;;
                *)
                    echo "  [${pbf_name}] WARN [${region}]: HTTP ${http_code} from ${TRIP_LOCAL_APP_BASE_URL}: ${body}"
                    ;;
            esac
        else
            echo "  [${pbf_name}] refreshed coverage for ${region}"
        fi
    done
    rm -f /tmp/coverage-refresh.out
}

# Sets last_apply_status = OK, records the deployed md5, auto-reschedules
# next_update_at if update_interval_days is non-null.
#
# Also folds the upstream-observation columns (last_check_at,
# last_remote_md5, next_check_at) into this UPDATE: do_apply just fetched
# the .md5 a moment ago, so we know the current upstream value. Writing it
# here keeps the stale-detection flag honest — without this update,
# last_remote_md5 would still reflect whatever the previous cheap check
# saw, which (if upstream rolled forward between then and the apply)
# differs from last_apply_md5 and renders the row as "STALE — newer pbf
# available" the moment after a fresh successful apply. Same reasoning
# applies to do_cheap_check: an apply IS a check, so we reset
# next_check_at on the same cadence.
record_apply_success() {
    local pbf_name="$1"
    local md5="$2"
    local update_interval_days="$3"
    local check_interval_days="$4"

    local next_update_clause="next_update_at = NULL"
    if [ -n "${update_interval_days}" ] && [ "${update_interval_days}" != " " ]; then
        next_update_clause="next_update_at = now() + INTERVAL '${update_interval_days} days'"
    fi

    psql_exec "UPDATE pbf_files SET
        last_apply_finished_at = now(),
        last_apply_md5 = lower('${md5}'),
        last_apply_status = 'OK',
        last_apply_error = NULL,
        last_check_at = now(),
        last_remote_md5 = lower('${md5}'),
        next_check_at = now() + INTERVAL '${check_interval_days} days',
        ${next_update_clause}
        WHERE pbf_name = '${pbf_name}'"

    enforce_single_loaded_pbf "${pbf_name}"
}

# Same as success but with status = NO_CHANGE — the apply fired but
# upstream md5 matched what's deployed, so no actual work was done.
# Folds in the same upstream-observation update as record_apply_success;
# we did fetch the .md5 in the apply path, and that value now stamps
# last_remote_md5 / last_check_at.
record_apply_no_change() {
    local pbf_name="$1"
    local md5="$2"
    local update_interval_days="$3"
    local check_interval_days="$4"

    local next_update_clause="next_update_at = NULL"
    if [ -n "${update_interval_days}" ] && [ "${update_interval_days}" != " " ]; then
        next_update_clause="next_update_at = now() + INTERVAL '${update_interval_days} days'"
    fi

    psql_exec "UPDATE pbf_files SET
        last_apply_finished_at = now(),
        last_apply_status = 'NO_CHANGE',
        last_apply_error = NULL,
        last_check_at = now(),
        last_remote_md5 = lower('${md5}'),
        next_check_at = now() + INTERVAL '${check_interval_days} days',
        ${next_update_clause}
        WHERE pbf_name = '${pbf_name}'"

    enforce_single_loaded_pbf "${pbf_name}"
}

# Single-loaded-pbf invariant.
#
# The current runOrs.sh / ors-config.yml architecture loads exactly one
# pbf at a time (one bind-mount at /home/ors/files/osm-file.osm.pbf).
# So at the moment any apply for pbf X succeeds (or comes back NO_CHANGE),
# X is the only locally routable extract and every other pbf row's
# polygon — left over from a previous apply — is stale signal: the
# dispatcher would route into the polygon, call local ORS, get no path,
# and fall back. We clear those rows' geom + fetched_at so the
# dispatcher's coversAll() ignores them (the filter is
# `enabled AND geom IS NOT NULL`).
#
# When admin re-applies a previously-loaded pbf, its polygon is re-fetched
# by the post-apply refresh step, so the previously-cleared geom comes
# back. last_apply_* columns on the other rows are left untouched —
# they're historical bookkeeping the admin can still see.
enforce_single_loaded_pbf() {
    local loaded_pbf_name="$1"
    local cleared
    cleared="$(psql_query "
        WITH cleared AS (
            UPDATE routing_coverage
               SET geom = NULL,
                   fetched_at = NULL
             WHERE name <> '${loaded_pbf_name}'
               AND geom IS NOT NULL
            RETURNING name
        )
        SELECT string_agg(name, ', ') FROM cleared")"
    if [ -n "${cleared}" ] && [ "${cleared}" != " " ]; then
        echo "  [${loaded_pbf_name}] single-loaded-pbf invariant: cleared polygon(s) for: ${cleared}"
    fi
}

# Sets last_apply_status to the failure stage + records the error message.
# Leaves next_update_at alone so the cron retries on the next tick.
record_apply_failure() {
    local pbf_name="$1"
    local status="$2"
    local error_message="$3"
    # Escape single quotes for SQL.
    local escaped
    escaped="$(echo "${error_message}" | sed "s/'/''/g")"

    psql_exec "UPDATE pbf_files SET
        last_apply_finished_at = now(),
        last_apply_status = '${status}',
        last_apply_error = '${escaped}'
        WHERE pbf_name = '${pbf_name}'"
}

# ============================================================================
# Main tick: stale-apply recovery first (cheap), then iterate active rows.
# ============================================================================

echo "[$(date -Iseconds)] trip-pbf-cron tick"
echo "  data dir: ${DATA_DIR}"
echo "  app base url: ${TRIP_LOCAL_APP_BASE_URL}"

do_stale_apply_recovery

# Pull the working set in one query so we have a consistent snapshot.
ROWS="$(psql_query "SELECT pbf_name, geofabrik_url, check_interval_days,
    COALESCE(update_interval_days::text, ''),
    COALESCE(last_apply_md5, ''),
    COALESCE(next_check_at::text, ''),
    COALESCE(next_update_at::text, '')
    FROM pbf_files
    WHERE active = TRUE
    ORDER BY pbf_name")"

if [ -z "${ROWS}" ]; then
    echo "  no active pbf_files rows; nothing to do"
    exit 0
fi

NOW_EPOCH="$(date +%s)"

while IFS='|' read -r pbf_name geofabrik_url check_interval_days update_interval_days last_apply_md5 next_check_at next_update_at; do
    [ -z "${pbf_name}" ] && continue

    echo "  [${pbf_name}] examining row"

    # Cheap check: gated by next_check_at unless --force is set.
    if [ "${FORCE_CHECK}" = "true" ]; then
        echo "    cheap check: forced via --force"
        do_cheap_check "${pbf_name}" "${geofabrik_url}" "${check_interval_days}" || true
    elif [ -n "${next_check_at}" ]; then
        next_check_epoch="$(date -d "${next_check_at}" +%s 2>/dev/null || echo 0)"
        if [ "${next_check_epoch}" -gt "${NOW_EPOCH}" ]; then
            echo "    cheap check: skipped (next at ${next_check_at}; pass --force to override)"
        else
            do_cheap_check "${pbf_name}" "${geofabrik_url}" "${check_interval_days}" || true
        fi
    else
        # No next_check_at means "do it now" (typical for freshly-added rows).
        do_cheap_check "${pbf_name}" "${geofabrik_url}" "${check_interval_days}" || true
    fi

    # Full apply: only when next_update_at is set and due. Log the skip
    # reason explicitly so the operator can see why nothing happened.
    if [ -z "${next_update_at}" ]; then
        echo "    apply: not scheduled (next_update_at IS NULL — use \"Schedule now\" in the admin Pbfs view to trigger one)"
    else
        next_update_epoch="$(date -d "${next_update_at}" +%s 2>/dev/null || echo 0)"
        if [ "${next_update_epoch}" -gt "${NOW_EPOCH}" ]; then
            echo "    apply: scheduled but not due (next at ${next_update_at})"
        else
            do_apply "${pbf_name}" "${geofabrik_url}" "${update_interval_days}" "${last_apply_md5}" "${check_interval_days}" || true
        fi
    fi

    # Polygon staleness self-heal — runs every tick regardless of apply
    # state. Cheap when everything's in sync; refreshes only the rows
    # whose fetched_at is older than last_apply_finished_at.
    check_and_refresh_stale_polygons "${pbf_name}"
done <<< "${ROWS}"

unset PGPASSWORD
echo "[$(date -Iseconds)] trip-pbf-cron tick complete"
