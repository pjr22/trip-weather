#!/bin/bash
#
# Phase 5a monthly OSM-pbf refresh for the trip-ors container. See
# LOCAL_CACHING_HOSTING.md.
#
# Workflow:
#   1. Download the latest .md5 from Geofabrik. Compare to the local copy.
#      If unchanged, exit (no-op — the OSM hasn't moved).
#   2. Download the new pbf to a temp file. Verify against the .md5.
#   3. Stop trip-ors, atomically swap the pbf, wipe the graph volume,
#      restart. ORS sees an empty graph dir and rebuilds from the new pbf.
#   4. POST /api/admin/refresh-coverage/{region} so the routing_coverage row
#      tracks the polygon Geofabrik may have re-clipped to. Without this the
#      dispatch wrapper could send a request the engine can't serve, the
#      fallback would catch it, but the metric would be misleading.
#
# Intended for host crontab; suggested schedule:
#   0 3 1 * *  /opt/trip-weather/docker/refreshOrsGraph.sh >> /var/log/trip-ors-refresh.log 2>&1
#
# Required env vars:
#
#   TRIP_ADMIN_REFRESH_TOKEN  Same value the trip-weather container sees in
#                             TRIP_ADMIN_REFRESH_TOKEN. Without it, step 4
#                             returns 403 and the script exits non-zero.
#
# Optional env vars:
#
#   GEOFABRIK_PBF_URL   Defaults to the Western US extract. For Colorado-
#                       only, set to .../north-america/us/colorado-latest.osm.pbf.
#   ORS_PBF_FILE        Basename of the local pbf. Must match what
#                       runOrs.sh expects (default us-west-latest.osm.pbf).
#   COVERAGE_REGIONS    Comma-separated region slugs to POST to
#                       /api/admin/refresh-coverage; one POST per region.
#                       Defaults to all 11 regions in the Western US extract.
#   APP_BASE_URL        Defaults to http://localhost:9080 (the trip-weather
#                       container's published port). Adjust if your
#                       host-side mapping differs.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA_DIR="${SCRIPT_DIR}/ors-data"

GEOFABRIK_PBF_URL="${GEOFABRIK_PBF_URL:-https://download.geofabrik.de/north-america/us-west-latest.osm.pbf}"
GEOFABRIK_MD5_URL="${GEOFABRIK_PBF_URL}.md5"
ORS_PBF_FILE="${ORS_PBF_FILE:-us-west-latest.osm.pbf}"
COVERAGE_REGIONS="${COVERAGE_REGIONS:-california,nevada,oregon,washington,idaho,utah,arizona,new-mexico,montana,wyoming,colorado}"
APP_BASE_URL="${APP_BASE_URL:-http://localhost:9080}"

if [ -z "${TRIP_ADMIN_REFRESH_TOKEN:-}" ]; then
    echo "ERROR: TRIP_ADMIN_REFRESH_TOKEN is not set." >&2
    exit 1
fi

mkdir -p "${DATA_DIR}"

PBF_PATH="${DATA_DIR}/${ORS_PBF_FILE}"
MD5_PATH="${PBF_PATH}.md5"
PBF_TMP="${PBF_PATH}.new"
MD5_TMP="${MD5_PATH}.new"

echo "[$(date -Iseconds)] Refreshing ${ORS_PBF_FILE}"

# Step 1: compare upstream .md5 to local. If identical, nothing to do.
curl -fsSL -o "${MD5_TMP}" "${GEOFABRIK_MD5_URL}"
if [ -f "${MD5_PATH}" ] && cmp -s "${MD5_PATH}" "${MD5_TMP}"; then
    echo "  upstream pbf unchanged; nothing to do"
    rm -f "${MD5_TMP}"
    exit 0
fi

# Step 2: fetch the pbf and verify the digest matches the .md5 we just got.
echo "  downloading new pbf..."
curl -fsSL -o "${PBF_TMP}" "${GEOFABRIK_PBF_URL}"

EXPECTED_HASH="$(awk '{print $1}' "${MD5_TMP}")"
ACTUAL_HASH="$(md5sum "${PBF_TMP}" | awk '{print $1}')"
if [ "${EXPECTED_HASH}" != "${ACTUAL_HASH}" ]; then
    echo "  ERROR: md5 mismatch (expected ${EXPECTED_HASH}, got ${ACTUAL_HASH})" >&2
    rm -f "${PBF_TMP}" "${MD5_TMP}"
    exit 1
fi
echo "  md5 verified: ${EXPECTED_HASH}"

# Step 3: stop ORS, swap, wipe graph, restart.
echo "  stopping trip-ors..."
docker stop trip-ors >/dev/null 2>&1 || true
docker rm   trip-ors >/dev/null 2>&1 || true

mv -f "${PBF_TMP}" "${PBF_PATH}"
mv -f "${MD5_TMP}" "${MD5_PATH}"

echo "  wiping graph volume so ORS rebuilds from new pbf..."
docker volume rm trip_ors_graph >/dev/null

echo "  restarting trip-ors (graph rebuild starts; takes ~30-60 min for Colorado)..."
"${SCRIPT_DIR}/runOrs.sh"

# Step 4: refresh the coverage polygon for each region. Geofabrik can
# re-clip extracts as OSM admin boundaries evolve, and the dispatch
# wrapper has to track that. Each POST is fast (single .poly fetch,
# ~100 KB); don't wait for the graph build to finish.
#
# Per-region failures are logged but don't fail the script — the pbf swap
# succeeded; any individual region's coverage drift is recoverable by
# re-running this script or POSTing manually for that one region.
echo "  refreshing routing_coverage for: ${COVERAGE_REGIONS}"
for region in ${COVERAGE_REGIONS//,/ }; do
    HTTP_CODE="$(curl -sS -o /tmp/coverage-refresh.out -w "%{http_code}" \
        -X POST \
        -H "X-Admin-Token: ${TRIP_ADMIN_REFRESH_TOKEN}" \
        "${APP_BASE_URL}/api/admin/refresh-coverage/${region}")"
    if [ "${HTTP_CODE}" != "200" ]; then
        echo "    WARN [${region}]: HTTP ${HTTP_CODE}: $(cat /tmp/coverage-refresh.out)" >&2
    else
        echo "    [${region}]: $(cat /tmp/coverage-refresh.out)"
    fi
done
rm -f /tmp/coverage-refresh.out

echo "[$(date -Iseconds)] Done."
