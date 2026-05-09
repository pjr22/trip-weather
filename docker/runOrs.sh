#!/bin/bash
#
# Phase 5a self-hosted OpenRouteService. See LOCAL_CACHING_HOSTING.md.
#
# Starts the official openrouteservice/openrouteservice image as a sidecar on
# forgotten_net. The Spring container reaches it via the docker-network name
# `trip-ors` on port 8082 (set TRIP_LOCAL_ORS_BASE_URL=http://trip-ors:8082/ors).
# Note: the ORS v8+ image listens on 8082 internally — *not* 8080, despite
# what older guides may say.
#
# Inputs (relative to this script's directory):
#
#   ors-data/<region>-latest.osm.pbf
#       OSM extract from Geofabrik. Set ORS_PBF_FILE to the basename
#       (default: us-west-latest.osm.pbf — covers 11 US states from the
#       Pacific to the Rockies, including Colorado). Place the file here
#       BEFORE running the script — refreshOrsGraph.sh handles fetching
#       it. To run the smaller Colorado-only graph instead, set
#       ORS_PBF_FILE=colorado-latest.osm.pbf and bump heap down to 1g/2g.
#
#   ors-config.yml
#       The ORS config baked next to the script. Profiles, elevation, and
#       endpoint exposure live here.
#
# Volumes:
#
#   trip_ors_graph (named) — the built graph, survives container restart.
#       Wipe with `docker volume rm trip_ors_graph` to force a rebuild on
#       next start (this is also what refreshOrsGraph.sh does after a pbf
#       swap).
#
# JVM heap defaults to XMS=4g, XMX=8g — enough for the Western US extract
# (steady state ~3.3 GB on-heap, ~8 GB RSS including JVM overhead and
# mmap'd graph data). For Colorado alone, XMS=1g XMX=2g is plenty.
#
# First start with an empty graph volume = ORS rebuilds the graph from the
# pbf. Expect ~30 min for Western US (~3 min for Colorado) before the API
# is ready. On subsequent starts the graph loads from disk in seconds.

set -eu

# Git Bash / MSYS on Windows mangles -v "host:container" args (interprets
# the colons as drive separators). These two env vars disable both
# path-conversion and arg-conversion for THIS script only.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA_DIR="${SCRIPT_DIR}/ors-data"
CONFIG_FILE="${SCRIPT_DIR}/ors-config.yml"

ORS_PBF_FILE="${ORS_PBF_FILE:-us-west-latest.osm.pbf}"
ORS_IMAGE="${ORS_IMAGE:-openrouteservice/openrouteservice:latest}"
ORS_XMS="${ORS_XMS:-4g}"
ORS_XMX="${ORS_XMX:-8g}"
ORS_PUBLISH_PORT="${ORS_PUBLISH_PORT:-8082}"

if [ ! -f "${CONFIG_FILE}" ]; then
    echo "ERROR: ${CONFIG_FILE} not found." >&2
    exit 1
fi
if [ ! -f "${DATA_DIR}/${ORS_PBF_FILE}" ]; then
    echo "ERROR: ${DATA_DIR}/${ORS_PBF_FILE} not found." >&2
    echo "       Run refreshOrsGraph.sh first, or place the pbf there manually." >&2
    exit 1
fi

docker volume create trip_ors_graph >/dev/null

docker run -d \
    --name trip-ors \
    -p "${ORS_PUBLISH_PORT}:8082" \
    -e XMS="${ORS_XMS}" \
    -e XMX="${ORS_XMX}" \
    -v "${CONFIG_FILE}:/home/ors/config/ors-config.yml:ro" \
    -v "${DATA_DIR}/${ORS_PBF_FILE}:/home/ors/files/osm-file.osm.pbf:ro" \
    -v trip_ors_graph:/home/ors/graphs \
    --net forgotten_net \
    "${ORS_IMAGE}"

echo "trip-ors started; published on host port ${ORS_PUBLISH_PORT}"
echo "  pbf:    ${ORS_PBF_FILE}"
echo "  config: $(basename "${CONFIG_FILE}")"
echo "  heap:   ${ORS_XMS} / ${ORS_XMX}"
echo
echo "First-start graph build takes ~30 min for Western US (~3 min for"
echo "Colorado). Watch progress:"
echo "  docker logs -f trip-ors"
echo "Container is ready when /v2/health returns {\"status\":\"ready\"}."
