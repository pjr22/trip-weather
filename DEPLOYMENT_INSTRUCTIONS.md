# Trip Weather — Deployment Instructions

Deployment guide for two target environments: **local development** (`./gradlew bootRun` on the host machine) and **production** (all components running as containers on a single Linux host).

Both assume the database schema is already in place — this document doesn't cover migrations. It treats the application as a ready-to-deploy product.

---

## Components

The full stack:

| Component | Role | Image / source |
|---|---|---|
| `tripdb` | PostgreSQL + PostGIS — application data, route storage, durable caches, pbf orchestration state | `postgis/postgis:18-3.6` |
| `trip-weather` | Spring Boot app (HTTP + JSON API + admin console at `/admin/`) | `org.pjr22/trip-weather:latest` (built from this repo) |
| `trip-ors` | Self-hosted OpenRouteService routing engine | `openrouteservice/openrouteservice:latest` |
| `tripnginx` | Caching reverse proxy — proxies app traffic + caches OSM tiles, WMS, weather icons | `nginx:1.27-alpine` |
| `docker/refreshOrsGraph.sh` | Host-side cron worker — manages pbf lifecycle (download, swap, rebuild graph, refresh coverage polygons) | shell script, run from host crontab |

External services every deployment talks to: Geofabrik (pbf files + polygon clips), api.weather.gov, OpenRouteService public (fallback when local ORS doesn't cover a region), GeoApify (geocoding), NREL/NLR (EV station data), and a Mailtrap-compatible REST sender (signup verification + password-reset emails). API keys for the latter four are required.

All containers run on the user-defined Docker bridge network **`forgotten_net`** so they can find each other by name.

---

## Local development

### Prerequisites

- JDK 21 (provided automatically by the Gradle wrapper toolchain)
- PostgreSQL with PostGIS, locally reachable on `localhost:5432`. Easiest: `./docker/runPostGis.sh` (uses `TRIP_DB_PASSWORD` from the environment to launch the `tripdb` container)
- API keys saved to plain-text files next to the repo, one secret per file (`*_api_key.txt`)

### One-time secret files

Place these in the **parent directory** of the repo (not inside it — they're gitignored if accidentally moved in). The values come from each service's dashboard:

| File | Contents |
|---|---|
| `../openRouteService_api_key.txt` | OpenRouteService API key (fallback routing) |
| `../geoApify_api_key.txt` | GeoApify API key (geocoding) |
| `../developer.nrel.gov_api_key.txt` | NREL/NLR API key (EV stations) |
| `../mailtrap_api_key.txt` | Mailtrap API token (only if `TRIP_EMAIL_ENABLED=true`) |
| `../trip_db_password.txt` | Postgres password for the `postgres` user |
| `../admin_refresh_token.txt` | A 32-char hex secret you generate once: `openssl rand -hex 32 > ../admin_refresh_token.txt` |
| `../remember_me_key.txt` | A signing key you generate once: `openssl rand -base64 48 > ../remember_me_key.txt` |
| `../admin_password.txt` | Plaintext password for the `/admin/` console. Generate once: `openssl rand -base64 24 > ../admin_password.txt` |

The companion script `setEnvVariables.source` reads these into env vars at the top of each dev session.

### Environment variables (dev)

| Variable | Nominal dev value | Purpose |
|---|---|---|
| `TRIP_DB_PASSWORD` | `<contents of trip_db_password.txt>` | Connect to local Postgres |
| `OPENROUTESERVICE_API_KEY` | `<key>` | Public ORS fallback |
| `GEOAPIFY_API_KEY` | `<key>` | Geocoding API |
| `NREL_API_KEY` | `<key>` | EV station mirror loader |
| `TRIP_EMAIL_ENABLED` | `false` | When false, signup-verification / password-reset emails are logged at INFO instead of sent. Most dev sessions don't need a Mailtrap inbox. |
| `TRIP_EMAIL_URL` | `https://sandbox.api.mailtrap.io/api/send/<inbox-id>` | Only required when `TRIP_EMAIL_ENABLED=true` |
| `TRIP_EMAIL_APIKEY` | `<token>` | Only required when `TRIP_EMAIL_ENABLED=true` |
| `TRIP_APP_BASE_URL` | `http://localhost:8090` | Public-facing base URL the app uses for outbound email links (signup verification, password reset). Match the bootRun port. |
| `TRIP_COOKIE_SECURE` | `false` | bootRun serves plain HTTP; sessions over `Secure` cookies require HTTPS. Production leaves this default `true`. |
| `TRIP_ADMIN_ENABLED` | `true` (or `false` to skip) | Master switch for the admin console at `/admin/`. When true, both `TRIP_ADMIN_USERNAME` and `TRIP_ADMIN_PASSWORD` are required. |
| `TRIP_ADMIN_USERNAME` | `admin` | Admin console login |
| `TRIP_ADMIN_PASSWORD` | `<any string>` | Admin console password (plaintext into env, BCrypt-hashed in memory at startup) |
| `TRIP_ADMIN_REFRESH_TOKEN` | `<contents of admin_refresh_token.txt>` | Shared secret for `X-Admin-Token` header (used by `refreshOrsGraph.sh` if you exercise the pbf cron locally). Must match across all components that send or receive this header. |
| `TRIP_REMEMBER_ME_ENABLED` | `true` (or `false` to skip) | "Stay logged in for 30 days" cookie feature |
| `TRIP_REMEMBER_ME_KEY` | `<contents of remember_me_key.txt>` | Required when `TRIP_REMEMBER_ME_ENABLED=true`; HMAC key for the cookie |
| `TRIP_LOCAL_ORS_ENABLED` | `false` (typical dev) | Set `true` only if you've started the `trip-ors` container locally. When false, every routing call goes to public ORS. |
| `TRIP_LOCAL_ORS_BASE_URL` | `http://localhost:8082/ors` | When local ORS is enabled, where to reach it (the host port the container publishes) |
| `TRIP_TILE_PROXY_ENABLED` | `false` | When false (typical dev), the SPA hits OSM and weather.gov directly. Flip true only when also running the nginx sidecar. |

Local ORS coverage is managed entirely via the admin console (`/admin/` → Data tab → Pbfs card) since ADMIN_CONSOLE.md Phase 2c. Fresh installs start with an empty `routing_coverage` table; admin adds a pbf row (the modal's region picker autocompletes US Geofabrik slugs) and the cron's post-apply step fetches the matching `.poly`. **At most one pbf is loaded into trip-ors at a time** — applying a second pbf replaces the first. Merging multiple pbfs into one engine is a planned follow-up.

### Run the app

```bash
# From the repo root, in a clean shell:
source setEnvVariables.source
export TRIP_ADMIN_USERNAME=admin
export TRIP_ADMIN_PASSWORD='dev-pass-do-not-share'
./gradlew bootRun
```

The app is now serving:
- SPA + JSON API at `http://localhost:8090/`
- Admin console at `http://localhost:8090/admin/` (log in with the credentials above)
- `/actuator/prometheus` and `/actuator/health` on the same port

### Optional dev extras

The two side-containers from the production setup can be brought up locally if you want to exercise the full path:

```bash
# Tile-cache reverse proxy (Phase 4 of LOCAL_CACHING_HOSTING.md).
# Browser then hits http://localhost:8091; bootRun still listens on 8090.
./docker/runNginx.sh
export TRIP_TILE_PROXY_ENABLED=true
export TRIP_TILE_PROXY_BASE_URL=http://localhost:8091

# Self-hosted ORS (Phase 5a of LOCAL_CACHING_HOSTING.md).
# Pulls a multi-GB pbf and rebuilds the graph (~30 min for us-west).
./docker/runOrs.sh
export TRIP_LOCAL_ORS_ENABLED=true
export TRIP_LOCAL_ORS_BASE_URL=http://localhost:8082/ors
```

---

## Production

### Prerequisites on the host

- Docker installed and running
- A user-defined Docker network: `docker network create forgotten_net`
- A directory layout under `/opt/trip-weather/` (or wherever you've cloned the repo) that includes the contents of this repo's `docker/` subdirectory
- The `trip-weather` Docker image already built and tagged as `org.pjr22/trip-weather:latest` (the artifact this script deploys; building it is upstream of this document)
- `psql` on PATH (the host cron script connects directly to Postgres)
- `flock` and `curl` available in the host shell environment
- Secret files (same layout as dev) in `/opt/trip-weather/` or a sibling location accessible to the startup scripts

### Filesystem layout on the host

```
/opt/trip-weather/
├── docker/
│   ├── ors-data/                        # pbf + .md5 files (volume-mounted into trip-ors)
│   │   └── us-west-latest.osm.pbf       # placed here by refreshOrsGraph.sh
│   ├── ors-config.yml
│   ├── nginx-default.conf.template
│   ├── refreshOrsGraph.sh               # host cron worker
│   ├── runPostGis.sh
│   ├── startTripWeather.source
│   ├── runOrs.sh
│   └── runNginx.sh
├── openRouteService_api_key.txt
├── geoApify_api_key.txt
├── developer.nrel.gov_api_key.txt
├── mailtrap_api_key.txt
├── trip_db_password.txt
├── admin_refresh_token.txt
├── remember_me_key.txt
└── admin_password.txt
```

### Environment variables — `trip-weather` container

The Spring application's env, set on `docker run`:

| Variable | Nominal prod value | Purpose |
|---|---|---|
| `SERVER_PORT` | `8080` | Internal port the container listens on |
| `TRIP_DB_URL` | `jdbc:postgresql://tripdb:5432/postgres` | Docker-network DNS name of the database container |
| `TRIP_DB_PASSWORD` | `<contents of trip_db_password.txt>` | Postgres password |
| `OPENROUTESERVICE_API_KEY` | `<key>` | Public ORS fallback when local ORS doesn't cover a region |
| `GEOAPIFY_API_KEY` | `<key>` | Geocoding API |
| `NREL_API_KEY` | `<key>` | EV station mirror loader |
| `TRIP_EMAIL_ENABLED` | `true` | Send real verification + reset emails |
| `TRIP_EMAIL_URL` | `https://send.api.mailtrap.io/api/send` | Mailtrap live API endpoint |
| `TRIP_EMAIL_APIKEY` | `<contents of mailtrap_api_key.txt>` | Mailtrap API token |
| `TRIP_EMAIL_FROM` | `tripweather@pjr22.com` | "From" address on outbound mail (must be a verified Mailtrap sender) |
| `TRIP_EMAIL_FROM_NAME` | `Trip Weather` | Display name on outbound mail |
| `TRIP_APP_BASE_URL` | `https://tripweather.pjr22.com` | **Public** hostname embedded in email links — must match what the user types into their browser |
| `TRIP_COOKIE_SECURE` | `true` (default) | Sessions over HTTPS only — required when an HTTPS terminator (haproxy) fronts the stack |
| `TRIP_ADMIN_ENABLED` | `true` | Enables the `/admin/` console |
| `TRIP_ADMIN_USERNAME` | `admin` (or another short slug) | Admin console login |
| `TRIP_ADMIN_PASSWORD` | `<long random>` | Admin console password |
| `TRIP_ADMIN_REFRESH_TOKEN` | `<contents of admin_refresh_token.txt>` | `X-Admin-Token` shared secret — **must match the value the host cron uses** |
| `TRIP_REMEMBER_ME_ENABLED` | `true` | "Stay logged in" cookie |
| `TRIP_REMEMBER_ME_KEY` | `<contents of remember_me_key.txt>` | HMAC key for remember-me cookies; rotating this invalidates every existing one |
| `TRIP_LOCAL_ORS_ENABLED` | `true` | Use the trip-ors sidecar; falls back to public ORS for points outside the loaded pbf's polygon. Pbf is managed via the admin console at `/admin/` (Data tab → Pbfs card). One pbf is loaded at a time. |
| `TRIP_LOCAL_ORS_BASE_URL` | `http://trip-ors:8082/ors` | Docker-network address of the routing engine |
| `TRIP_TILE_PROXY_ENABLED` | `true` | SPA fetches tiles + weather icons through the tripnginx proxy instead of upstream public services |
| `TRIP_TILE_PROXY_BASE_URL` | `` (empty) | Empty = relative paths (SPA traffic comes through tripnginx, so paths like `/tiles/osm/...` resolve correctly without a host prefix) |

### Environment variables — host cron (running `docker/refreshOrsGraph.sh`)

Set these in the crontab's environment (e.g. `/etc/cron.d/trip-pbf` or via a wrapper script that sources `/opt/trip-weather/cron.env`):

| Variable | Nominal prod value | Purpose |
|---|---|---|
| `TRIP_DB_PASSWORD` | `<contents of trip_db_password.txt>` | Cron reads `pbf_files` directly via psql |
| `TRIP_DB_HOST` | `localhost` | The tripdb container publishes 5432 on the host |
| `TRIP_ADMIN_REFRESH_TOKEN` | `<contents of admin_refresh_token.txt>` | **Must equal** the trip-weather container's value — the `X-Admin-Token` header has to match what the app validates against |
| `TRIP_LOCAL_APP_BASE_URL` | `http://localhost:8091` | Where the cron POSTs `/api/admin/refresh-coverage/{region}` from the host — the nginx proxy port. Distinct from `TRIP_APP_BASE_URL` (the app's public self-URL). |

### Environment variables — other containers

The remaining containers carry a small subset:

| Container | Variable | Value | Purpose |
|---|---|---|---|
| `tripdb` | `POSTGRES_PASSWORD` | `<contents of trip_db_password.txt>` | Bootstrap the `postgres` superuser |
| `tripnginx` | `BACKEND_UPSTREAM` | `trip-weather:8080` | Where nginx forwards catch-all requests (the Spring container on `forgotten_net`) |
| `tripnginx` | `NGINX_ENVSUBST_FILTER` | `^BACKEND_UPSTREAM$` | Restrict envsubst so nginx-internal `$variables` survive |
| `trip-ors` | `XMS` | `4g` | Initial JVM heap (default in `runOrs.sh`; bump down to `1g` for Colorado-only) |
| `trip-ors` | `XMX` | `8g` | Max JVM heap (default; bump down to `2g` for Colorado-only) |

### Bring the stack up

Each script lives in `docker/` and pulls its environment from the host shell or the secret files. Run from `/opt/trip-weather/` (so the relative `cat ../*.txt` lookups resolve correctly):

```bash
cd /opt/trip-weather

# 1. Database — must be running before trip-weather starts.
./docker/runPostGis.sh

# 2. Routing engine — needs a pbf in docker/ors-data/ first.
#    On a fresh install, place us-west-latest.osm.pbf there and run:
./docker/runOrs.sh
# Note: trip-ors loads exactly one .osm.pbf at a time (single bind-mount
# at /home/ors/files/osm-file.osm.pbf). After bring-up, admin manages
# pbfs via /admin/ → Data tab → Pbfs card. Applying a second pbf
# replaces the first; only the most recently applied extract is locally
# routable. Merging multiple pbfs is a planned follow-up — see
# ADMIN_CONSOLE.md Phase 2c "Single-loaded-pbf constraint".

# 3. Reverse proxy — points at the trip-weather container (not bootRun).
BACKEND_UPSTREAM=trip-weather:8080 ./docker/runNginx.sh

# 4. The Spring app. Use ./docker/startTripWeather.source as a template;
#    it sources every env var the container needs.
cd docker
source startTripWeather.source     # this also runs `docker run --name trip-weather ...`
```

Note: `startTripWeather.source` sources every env var from the **Environment variables — `trip-weather` container** table above and then runs `docker run --name trip-weather ...`. Run it once for the initial start, then `docker stop trip-weather && docker rm trip-weather` followed by re-sourcing it for restarts.

### Cron entry for pbf orchestration

The host cron worker (`refreshOrsGraph.sh`) runs once a minute. It only does work when something is actually due — the cheap upstream `.md5` check is gated by `next_check_at`, the full apply by `next_update_at`, and the polygon staleness self-heal by comparing `routing_coverage.fetched_at` against `pbf_files.last_apply_finished_at`.

Recommended crontab entry (replace `<deployment_user>` with the user who owns `/opt/trip-weather/`):

```
* * * * * <deployment_user>  source /opt/trip-weather/cron.env && /opt/trip-weather/docker/refreshOrsGraph.sh >> /var/log/trip-pbf-cron.log 2>&1
```

Where `/opt/trip-weather/cron.env` is a sidecar file you create with the four env vars from the **host cron** table above:

```bash
# /opt/trip-weather/cron.env
export TRIP_DB_PASSWORD="$(cat /opt/trip-weather/trip_db_password.txt)"
export TRIP_DB_HOST=localhost
export TRIP_ADMIN_REFRESH_TOKEN="$(cat /opt/trip-weather/admin_refresh_token.txt)"
export TRIP_LOCAL_APP_BASE_URL=http://localhost:8091
```

### Public-facing termination (haproxy or equivalent)

This document doesn't include haproxy configuration — that lives outside the repo. The relevant entrypoint on this host is:

- **HTTPS terminator** → reverse-proxy to `http://127.0.0.1:8091` (the published port of `tripnginx`). nginx then forwards to `trip-weather` and serves cached tiles / weather imagery directly.

The terminator must forward `X-Forwarded-*` headers correctly (host, proto, port) so the Spring app builds redirects with the right scheme.

### Verification

```bash
# Containers running:
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
# Expect: tripdb, trip-ors, tripnginx, trip-weather all "Up"

# Spring app health:
curl http://localhost:8091/actuator/health
# {"status":"UP"}

# Admin console reachable via the proxy:
curl -sI http://localhost:8091/admin/login.html | head -1
# HTTP/1.1 200 OK

# Routing engine ready (this can take ~30 min on first start while the graph builds):
curl http://localhost:8082/ors/v2/health
# {"status":"ready"} when the graph is loaded

# Pbf cron output (after one tick has run):
tail -50 /var/log/trip-pbf-cron.log
```

---

## Restart / rollback

- **App-only restart** (after deploying a new image): `docker stop trip-weather && docker rm trip-weather` then re-source `startTripWeather.source`. Other containers untouched.
- **Routing engine restart**: `docker stop trip-ors && docker rm trip-ors && ./docker/runOrs.sh`. The graph stays in the `trip_ors_graph` named volume across restarts; no rebuild unless you `docker volume rm trip_ors_graph` first.
- **Database restart**: `docker stop tripdb && docker rm tripdb && ./docker/runPostGis.sh`. Data persists in the postgres image's default volume (if you ran with no `-v`, data is in the container — see below).
- **Full stack down**: stop and remove in reverse dependency order: `trip-weather`, `tripnginx`, `trip-ors`, then `tripdb`.

**Volumes worth knowing about:**

| Volume | Container | What's in it |
|---|---|---|
| `trip_ors_graph` (named) | `trip-ors` | Built routing graph. Survives container recreate; wipe to force rebuild from current pbf. |
| `trip_tile_cache` (named) | `tripnginx` | Cached OSM tiles + WMS + weather icons. Safe to wipe; nginx repopulates on demand. |
| postgres data | `tripdb` | The default `postgis/postgis` image stores data inside the container; mount a named volume on `/var/lib/postgresql/data` if you want it to survive a container delete. |

---

## Where to look when things go wrong

- **App logs**: `docker logs trip-weather` (or `docker logs -f` to follow)
- **Routing engine logs**: `docker logs trip-ors` — first start shows the graph build progress
- **Reverse proxy logs**: `docker logs tripnginx`
- **Cron pbf worker**: `/var/log/trip-pbf-cron.log` (or wherever your crontab redirects it)
- **Database**: `docker exec -it tripdb psql -U postgres` for interactive queries
- **Admin console**: `/admin/` → Data tab is the single best place to see what the loaders + pbf cron are actually doing (last-run state, history, error messages)
- **Metrics**: `/actuator/prometheus` (via the proxy: `http://localhost:8091/actuator/prometheus`) — every counter the app exposes, including `trip_routing_*` and `http_server_requests_seconds_*`
