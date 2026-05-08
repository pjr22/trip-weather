#!/bin/bash
#
# Phase 4 caching reverse proxy. See LOCAL_CACHING_HOSTING.md.
#
# Two operating modes, selected via BACKEND_UPSTREAM:
#
#   * dev (default): catch-all forwards to the host machine's bootRun
#     (host.docker.internal:8090), so `./gradlew bootRun` keeps working.
#     Browser hits http://localhost:8091 to go through nginx, or
#     http://localhost:8090 to bypass it (direct upstream URLs, requires
#     TRIP_TILE_PROXY_ENABLED=false).
#
#   * prod: set BACKEND_UPSTREAM=tripapp:8080 (the Spring container's
#     internal address on forgotten_net) before running this script.
#     The container's port 8090 is published as 8091 on host so haproxy
#     can target 127.0.0.1:8091.
#
# Tile cache lives in a named Docker volume so it survives container
# rebuilds; nuke it with `docker volume rm trip_tile_cache` for a clean slate.
#
# NGINX_ENVSUBST_FILTER restricts envsubst to BACKEND_UPSTREAM only, so
# nginx-internal $variables (e.g. $host, $is_args, $1) are left alone.

set -eu

# Git Bash / MSYS on Windows otherwise mangles the -v "host:container:ro"
# arguments: it interprets every : as a drive separator, splits the option
# into bogus path fragments, and prepends the MSYS install root to the
# container side. Result is a silent bind-mount failure that leaves nginx
# falling back to its built-in default.conf. These two env vars disable
# both the path-conversion and the arg-conversion passes for THIS script,
# without affecting the calling shell.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

# Pin to the script's own directory so the relative file path resolves
# correctly regardless of where the user invokes the script from.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE_FILE="${SCRIPT_DIR}/nginx-default.conf.template"

if [ ! -f "${TEMPLATE_FILE}" ]; then
    echo "ERROR: ${TEMPLATE_FILE} not found." >&2
    exit 1
fi

BACKEND_UPSTREAM="${BACKEND_UPSTREAM:-host.docker.internal:8090}"

docker volume create trip_tile_cache >/dev/null

docker run -d \
    --name tripnginx \
    -p 8091:8090 \
    -e BACKEND_UPSTREAM="${BACKEND_UPSTREAM}" \
    -e NGINX_ENVSUBST_FILTER="^BACKEND_UPSTREAM$" \
    -v "${TEMPLATE_FILE}:/etc/nginx/templates/default.conf.template:ro" \
    -v trip_tile_cache:/var/cache/nginx \
    --add-host=host.docker.internal:host-gateway \
    --net forgotten_net \
    nginx:1.27-alpine

echo "tripnginx started; published on host port 8091"
echo "  catch-all → ${BACKEND_UPSTREAM}"
