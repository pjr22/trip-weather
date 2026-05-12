# Admin Console — design and phased plan

A separate `/admin` SPA, gated by a single admin credential supplied via Spring properties (and overridable by environment variables). Five phases, each shippable on its own.

## Phase status

| Phase | Status |
|---|---|
| 0 — Authentication & shell | **Done** (2026-05-10). Two-chain SecurityConfig + namespaced session attribute, JSON login/logout/me, X-Admin-Token coexists on `refresh-coverage` only, login → empty shell. Smoke-tested live; 161/161 unit tests pass. |
| 1 — Route management | **Done** (2026-05-10). `routes.deleted_at` + `@SQLRestriction`, native-SQL admin paths, two-stage cleanup (soft → grace → hard), four admin endpoints, Routes view with search / owner / show-deleted toggle, sortable paginated table. |
| 2 — Loader & data management | **Done** (2026-05-10). `loader_runs` table + recorder + concurrency guard, manual triggers for cleanup / EV / ORS coverage, sequential refresh-all, polling Data view, legacy `refresh-coverage` endpoint records as CRON. |
| 2b — Pbf orchestration | **Done** (2026-05-10). `pbf_files` table + cron-rewrite of `docker/refreshOrsGraph.sh` (table-driven, per-minute, flock-guarded), Pbfs UI card replacing ORS Coverage card, JVM-side manual md5 check, retry-stuck-apply endpoint, polygon staleness self-heal independent of apply gate, migration auto-seeds from `docker/ors-data/`. Live-smoke-tested in dev. |
| 2c — Region / pbf collapse | **Done** (2026-05-11). One `routing_coverage` row per pbf (name == pbf_name, CASCADE FK); `trip.routing.local-regions` removed; Routing column + Enable/Disable toggle on Pbfs card; `PATCH /api/admin/pbfs/{name}` extended with `routingEnabled`. Plus single-loaded-pbf invariant in the cron (one extract loaded at a time; merging multiple is the deferred follow-up), filterable region picker in a modal add/edit form, shared toast utility extracted to `/static/js/utils/Toast.js` for both SPAs. 237 tests pass. |
| 3 — Metrics dashboard | Not started. |
| 4 — User management | Not started. |
| 5 — Hardening (optional) | Not started. |

## Decisions already made

These shape every phase below; revisit only if requirements change.

| Decision | Choice |
|---|---|
| Auth realm | Separate. Admin credential lives only in properties; never a row in `users`. |
| Frontend layout | Standalone `/admin/` static bundle under `src/main/resources/static/admin/`. The main SPA stays untouched and never downloads admin code. |
| Legacy `X-Admin-Token` on `/api/admin/refresh-coverage/{region}` | Keep working, alongside session auth. Production cron (`docker/refreshOrsGraph.sh`) needs no changes. |
| Number of admin credentials | Single. |
| Route deletion semantics | Soft delete (`routes.deleted_at`). |
| Loader observability | Persisted run history in `loader_runs`. |
| Metrics view | Curated dashboard reading `MeterRegistry`, plus link to raw `/actuator/prometheus`. |
| User-management actions | List/search/sort/paginate, enable, disable, force-verify, delete (with cascade). |
| Audit log | Deferred. Phase 5 may revisit; not required for v1. |

## Cross-cutting design points

### Two SecurityFilterChains, two cookies

The existing `SecurityConfig` becomes two ordered chains:

1. **`adminSecurityChain`** (`@Order(1)`), `securityMatcher("/admin/**", "/api/admin/**")`. Uses an in-memory `AdminUserDetailsService` populated from `trip.admin.*` properties, BCrypt-hashed at startup. Form login disabled — JSON `POST /api/admin/login` produces a session. CSRF off, matching the main SPA pattern; cookie is `HttpOnly` + `SameSite=Strict`.
2. **`userSecurityChain`** (`@Order(2)`), unchanged from today.

Critical detail: the two chains must use **different session-cookie names** (`ADMIN_JSESSIONID` vs default `JSESSIONID`) so that an admin login in the same browser cannot bleed a `ROLE_ADMIN` principal into user-chain requests (which would break `CurrentUserService.currentUserOrGuest()` — the admin is not a row in `users`). Configured via `DefaultCookieSerializer` on the admin chain's `HttpSessionIdResolver`.

### `X-Admin-Token` coexistence

`XAdminTokenAuthenticationFilter` runs before the admin chain's username/password filter. When the request carries a matching `X-Admin-Token` and `trip.admin.refresh-token` is non-blank, it sets an `Authentication` with authority `ROLE_ADMIN_TOKEN` (deliberately *not* `ROLE_ADMIN`). Only `POST /api/admin/refresh-coverage/{region}` is configured `hasAnyRole('ADMIN','ADMIN_TOKEN')`; every other admin endpoint requires `hasRole('ADMIN')`. So the legacy header keeps the cron working without granting it console-wide access.

### Properties

Append to [application.properties](src/main/resources/application.properties):

```properties
# Admin console — single operator credential.
# trip.admin.username and trip.admin.password are required when trip.admin.enabled=true.
# StartupConfigValidator fails fast at boot if either is blank.
trip.admin.enabled=${TRIP_ADMIN_ENABLED:true}
trip.admin.username=${TRIP_ADMIN_USERNAME:}
trip.admin.password=${TRIP_ADMIN_PASSWORD:}
# trip.admin.refresh-token is the existing X-Admin-Token shared secret (already in use today).
# Continues to grant access only to POST /api/admin/refresh-coverage/{region}.
```

The password is supplied in plaintext via env var, BCrypt-hashed once at boot and never logged. `StartupConfigValidator` extends to require both values when `trip.admin.enabled=true`. To skip admin entirely on a fresh checkout (e.g. iterating on something unrelated), set `TRIP_ADMIN_ENABLED=false`.

### Schema migration

New idempotent script [dev_scripts/admin-console-db-migration.sh](dev_scripts/admin-console-db-migration.sh), sibling to `dev_scripts/user-accounts-db-migration.sh` and `dev_scripts/local-caching-db-migration.sh`. Single BEGIN/COMMIT, same env-var conventions. Contents grow per phase below. The user runs migrations manually — phases below describe the SQL the script should contain at each stage.

### Admin SPA shell

Under `src/main/resources/static/admin/`:

```
admin/
  login.html              # plain form posting JSON to /api/admin/login
  index.html              # left-nav shell: Routes, Data, Metrics, Users
  admin.css               # table-heavy, no map dependencies
  js/
    AdminApp.js           # boots; calls /api/admin/me; redirects to login on 401
    api.js                # fetch wrapper: 401 -> redirect to /admin/login.html
    managers/
      RoutesView.js       # phase 1
      DataView.js         # phase 2
      MetricsView.js      # phase 3
      UsersView.js        # phase 4
```

No framework — vanilla JS, matching the main SPA's style. Admin CSS is fully separate (no shared variables) so the layouts don't drift into each other.

---

## Phase 0 — Authentication and shell

Land the auth scaffolding and an empty console with working login/logout. No business endpoints yet; sections show "(coming in phase N)".

### Backend

- `AdminAuthProperties` (`@ConfigurationProperties("trip.admin")`): `enabled`, `username`, `password`, `refreshToken`.
- `AdminUserDetailsService implements UserDetailsService`: BCrypts the configured password at startup; loads the single admin user with `ROLE_ADMIN`. Throws on any other username.
- `XAdminTokenAuthenticationFilter`: pre-auth filter that sets a `ROLE_ADMIN_TOKEN` principal on a matching header.
- `SecurityConfig` refactored into the two ordered chains described above.
- `StartupConfigValidator` extended: if `trip.admin.enabled=true`, both username and password must be non-blank.
- Endpoints:
  - `POST /api/admin/login` `{username, password}` → 200 (sets cookie) or 401.
  - `POST /api/admin/logout` → 200, invalidates session.
  - `GET /api/admin/me` → `{username, role: 'ADMIN'}` or 401.

### Frontend

- `admin/login.html` — single centered card; submits JSON to `/api/admin/login`; on success redirects to `/admin/`.
- `admin/index.html` — left nav with four placeholder sections, top-right username + Logout. `AdminApp.js` checks `/api/admin/me` on load.

### Tests

- `AdminUserDetailsServiceTest`: configured username matches, others rejected.
- `SecurityConfigAdminTest`: login round-trip, `/api/admin/me` 401 without session, logout invalidates.
- `XAdminTokenFilterTest`: header grants `ROLE_ADMIN_TOKEN` only, not `ROLE_ADMIN`; works on `/refresh-coverage/{region}`, denied on other admin endpoints.
- `StartupConfigValidatorTest`: missing `trip.admin.username` or `trip.admin.password` aborts boot when admin enabled.

### Migration

`dev_scripts/admin-console-db-migration.sh` created as a no-op stub (script structure, BEGIN/COMMIT, no DDL yet).

### Done when

- `TRIP_ADMIN_USERNAME=alice TRIP_ADMIN_PASSWORD=… ./gradlew bootRun` boots; visiting `http://localhost:8090/admin/` redirects to login; logging in lands on the empty shell; logging out and revisiting redirects again. ✓
- The production cron's `curl -H "X-Admin-Token: …" .../refresh-coverage/{region}` still succeeds. ✓ (verified end-to-end against a real bootRun)

---

## Phase 1 — Route management

Enable the admin to find, soft-delete, restore, and trigger cleanup of any route.

### Schema

```sql
ALTER TABLE routes ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ NULL;
CREATE INDEX IF NOT EXISTS routes_deleted_at_idx
  ON routes(deleted_at) WHERE deleted_at IS NOT NULL;
```

### Read-path filtering

Add `@SQLRestriction("deleted_at IS NULL")` to the `Route` entity so every JPA path automatically hides soft-deleted rows. Admin queries that need to surface deleted routes go through a small native-query repo method that bypasses the restriction. This is safer than touching every existing query individually.

### Cleanup-job change

`GuestRouteCleanupJob.cleanGuestRoutes()` shifts from one-shot hard delete to a two-stage purge:

1. Soft-delete guest routes whose `created` is older than `route.cleanup.retention-days` (default 30) and `deleted_at IS NULL`.
2. Hard-delete any route (guest or otherwise) whose `deleted_at` is older than `route.cleanup.purge-grace-days` (default 7).

A new property `trip.routes.cleanup.purge-grace-days=${ROUTE_CLEANUP_PURGE_GRACE_DAYS:7}` controls stage 2. Documented in [CLAUDE.md](CLAUDE.md) env-var table.

### Endpoints

- `GET  /api/admin/routes?q=&owner=&deleted=&page=&size=&sort=` — paginated list. Returns `{id, name, ownerEmail, ownerKind: USER|GUEST, waypointCount, created, deletedAt}`.
- `DELETE /api/admin/routes/{id}` — soft-delete (sets `deleted_at = now()`).
- `POST /api/admin/routes/{id}/restore` — clears `deleted_at`.
- `POST /api/admin/cleanup/trigger` — fires `cleanGuestRoutes()` asynchronously, returns 202 + run id (run id surfaces in phase 2).

### Frontend

`admin/routes.html` (and `RoutesView.js`): search box (matches name + owner email), owner-kind filter, "Show deleted" toggle, sortable columns, page controls. Per-row Delete / Restore actions; "Trigger cleanup" button at the top.

### Tests

- Soft-deleted route invisible to existing public load endpoint; visible to admin list when `deleted=true`.
- Restore round-trips.
- Cleanup hard-deletes routes whose `deleted_at` is older than `purge-grace-days`.
- `RoutePersistenceService` ownership rules unchanged for non-admin paths (admin path bypasses ownership by virtue of the new endpoint, not by relaxing the service).

### Done when

- Admin can search any user's routes by email, soft-delete one, restore it, and trigger cleanup. The user side of the app is unchanged — the user simply sees the route disappear.

---

## Phase 2 — Loader and data management

Persist a run history for every scheduled job and expose manual triggers.

### Schema

```sql
CREATE TABLE IF NOT EXISTS loader_runs (
  id            BIGSERIAL PRIMARY KEY,
  loader_name   VARCHAR(64)  NOT NULL,
  trigger_type  VARCHAR(16)  NOT NULL,   -- CRON | MANUAL | BOOTSTRAP
  started_at    TIMESTAMPTZ  NOT NULL,
  finished_at   TIMESTAMPTZ,
  status        VARCHAR(16)  NOT NULL,   -- RUNNING | SUCCESS | FAIL
  rows_affected BIGINT,
  error_message TEXT
);
CREATE INDEX IF NOT EXISTS loader_runs_loader_started_idx
  ON loader_runs(loader_name, started_at DESC);
```

### Code

- `LoaderRun` entity + `LoaderRunRepository`.
- `LoaderRunRecorder` service: `start(name, trigger) → LoaderRun`, `success(run, rows)`, `fail(run, ex)`. Persists each transition.
- Wrap the existing job bodies:
  - [GuestRouteCleanupJob](src/main/java/com/pjr22/tripweather/scheduler/GuestRouteCleanupJob.java) — separate runs for `guest-route-cleanup` and `email-token-cleanup`.
  - [EvStationLoader](src/main/java/com/pjr22/tripweather/service/EvStationLoader.java) — name `ev-stations`. Bootstrap-on-empty records `BOOTSTRAP`; cron records `CRON`.
  - [GeofabrikCoverageLoader](src/main/java/com/pjr22/tripweather/routing/GeofabrikCoverageLoader.java) — one run per region: name `ors-coverage:{region}`.
- Concurrency guard: a manual trigger is rejected (409) when a run with `status=RUNNING` exists for the same loader name; cron entry-points likewise skip if a manual run is in flight.

### Endpoints

- `GET  /api/admin/loaders` — list of known loader names with their last run summary.
- `GET  /api/admin/loaders/{name}/runs?limit=20` — history.
- `POST /api/admin/loaders/{name}/trigger` — kicks off async run, returns 202 + run id. For `ors-coverage`, body `{regions: [...]}` (defaults to `trip.routing.local.regions`).

### Frontend

`admin/data.html` (and `DataView.js`): three cards — Routes cleanup, EV stations, ORS coverage. Each shows last run status + timestamps + history table + Trigger button. Polling every 5 s while a run is `RUNNING`.

### Tests

- Recorder writes RUNNING → SUCCESS with `rows_affected`.
- Throwing loader writes RUNNING → FAIL with truncated `error_message`.
- Manual trigger while another run is RUNNING returns 409.
- Cron entry path uses `trigger=CRON`; admin endpoint uses `trigger=MANUAL`; `EvStationLoader.onApplicationReady` uses `trigger=BOOTSTRAP`.

### Done when

- Every cron tick and manual trigger appears as a row in `loader_runs`. The console shows the last 20 of each. Admin can fire any of the three loaders and watch the row flip from RUNNING to SUCCESS or FAIL.

---

## Phase 2b — Pbf orchestration

Bring all `.osm.pbf` lifecycle state into a database table the admin console controls, and refactor `docker/refreshOrsGraph.sh` from a once-per-month single-pbf script into a minute-frequency table-driven worker. Admin console becomes the operator's window into "what extracts am I serving, are they fresh, when's the next rebuild scheduled" — without any in-JVM docker access.

### Architecture

Two responsibilities that this design separates cleanly:

- **Host-side work** (download multi-GB pbf, swap file, wipe graph volume, restart trip-ors container, wait for `/ors/v2/health`, then call `/api/admin/refresh-coverage/{region}` per region) — stays in `docker/refreshOrsGraph.sh`, runs from host crontab. Has filesystem and docker access; trip-weather doesn't.
- **Orchestration state** (which pbfs are managed, when each is due for processing, last-known remote md5, last-applied md5, last-run status / error) — lives in the new `pbf_files` table. Admin console edits rows; cron reads them.

### Schema

```sql
CREATE TABLE pbf_files (
  pbf_name             VARCHAR(64) PRIMARY KEY,           -- 'us-west', 'colorado', 'us-central', etc.
  geofabrik_url        TEXT NOT NULL,                     -- absolute URL to the .pbf (the .md5 is at .pbf.md5)
  active               BOOLEAN NOT NULL DEFAULT TRUE,     -- when false, cron ignores the row entirely

  -- Cheap-check schedule. Auto-rescheduled by the cron after each check
  -- using check_interval_days. NULL next_check_at means "do it on the
  -- next tick" — typical for freshly-added rows.
  check_interval_days  INTEGER NOT NULL DEFAULT 7,
  next_check_at        TIMESTAMPTZ,

  -- Full-apply schedule. Default behaviour is admin-driven: the admin
  -- clicks "Schedule now" (or sets next_update_at directly) and the cron
  -- picks it up on the next tick. If update_interval_days is non-null,
  -- the cron auto-reschedules next_update_at after each successful apply.
  -- NULL next_update_at = no apply scheduled (paused).
  update_interval_days INTEGER,
  next_update_at       TIMESTAMPTZ,

  -- State written by the cron / admin actions:
  last_check_at        TIMESTAMPTZ,                       -- when cron (or admin "Check now") last fetched upstream .md5
  last_remote_md5      VARCHAR(32),                       -- md5 Geofabrik served at last_check_at
  last_remote_modified TIMESTAMPTZ,                       -- Last-Modified header from upstream

  last_apply_started_at  TIMESTAMPTZ,                     -- doubles as a stale-detection signal when an apply is in flight
  last_apply_finished_at TIMESTAMPTZ,
  last_apply_md5         VARCHAR(32),                     -- md5 of the pbf currently deployed (matches latest upstream when up-to-date)
  last_apply_status      VARCHAR(16),                     -- OK | NO_CHANGE | CHECK_FAILED | DOWNLOAD_FAILED | BUILD_FAILED | RESTART_FAILED
  last_apply_error       TEXT
);

ALTER TABLE routing_coverage
  ADD COLUMN IF NOT EXISTS pbf_name VARCHAR(64) REFERENCES pbf_files(pbf_name) ON DELETE SET NULL;
```

Two decoupled schedules per row — admin can manually trigger either without affecting the other:

- **Check schedule**: cron auto-reschedules every `check_interval_days` (default 7). Cheap (~50 bytes per check), used to keep `last_remote_md5` fresh in the admin UI so staleness is visible.
- **Apply schedule**: admin-driven by default (`update_interval_days IS NULL`). Setting `update_interval_days = 30` opts into monthly auto-rebuild attempts; the cron auto-reschedules `next_update_at` after each successful apply.

`routing_coverage.pbf_name` is the back-link the cron uses to know which polygons to refresh after applying a specific pbf. `ON DELETE SET NULL` so removing a pbf row doesn't cascade into the coverage table — polygons just lose their link.

Bootstrap seed in the migration: after creating the table, the script scans `docker/ors-data/` for `*.osm.pbf` files and INSERTs one `pbf_files` row per file it finds. Geofabrik URLs are derived from a name → URL map of common slugs (US sub-region extracts, individual states, Canada, Mexico); unknown slugs get a clearly-marked placeholder URL the operator fixes via the admin UI. If the file has a sibling `.md5`, that hash seeds `last_apply_md5` so the admin's freshness widget lights up on first load. When exactly one pbf was seeded, the migration also backfills `routing_coverage.pbf_name` for rows that were NULL — single-extract deployments don't need any manual linking step. Multi-extract deployments leave the backfill to the operator (per-region choice).

Re-running the migration is safe: the seed uses `ON CONFLICT (pbf_name) DO NOTHING`, and the backfill targets only rows with `pbf_name IS NULL`.

### Cron logic (new shape of `docker/refreshOrsGraph.sh`)

Crontab change: from `0 3 1 * *` (monthly) to `* * * * *` (every minute). Host-level `flock` so two ticks can't overlap regardless of duration:

```bash
exec 9>/var/lock/trip-pbf-cron.lock
flock -n 9 || exit 0
```

Per tick:

1. Query `SELECT * FROM pbf_files WHERE active = TRUE`.
2. For each row, **cheap upstream check (only when due)**: if `next_check_at IS NULL OR next_check_at <= now()`, `GET <geofabrik_url>.md5`, write `last_check_at` + `last_remote_md5` + `last_remote_modified`, set `next_check_at = now() + check_interval_days`. Manual check from the admin console hits a JVM-side endpoint that does the same fetch but **leaves `next_check_at` alone** — manual checks are independent of the cron's automatic schedule.
3. **Stale-apply recovery**: if a row has `last_apply_started_at` set, `last_apply_finished_at` null, and `last_apply_started_at` older than **4 hours**, treat as a crashed/killed previous apply — clear the marker and proceed.
4. For each row whose `next_update_at IS NOT NULL AND next_update_at <= now()`, perform the **full apply**. Fetch the upstream `.md5` first; if it matches `last_apply_md5`, record `last_apply_status = 'NO_CHANGE'` and skip the heavy pbf work. Otherwise (md5 differs): download the pbf, verify against `.md5`, stop trip-ors, swap file, wipe `trip_ors_graph` volume, run `runOrs.sh`, wait for `/ors/v2/health`, then refresh **every** linked region's polygon. On success: `last_apply_md5 = remote_md5`, `last_apply_status = 'OK'`. On any stage failure: `last_apply_status` records the stage, `last_apply_error` captures the stderr, `next_update_at` is left alone so it retries on the next tick.
5. For every active row (every tick, regardless of whether check or apply ran), do the **polygon staleness self-heal**: a single SELECT against `routing_coverage` joined with `pbf_files` finds rows whose `fetched_at < last_apply_finished_at` (or `fetched_at IS NULL`). If any exist, POST `/api/admin/refresh-coverage/{region}` for just those rows. Common case (everything in sync) is the one SELECT and a "polygons in sync with last apply" log line. This is the recovery path for failures of an earlier apply's polygon-refresh step — operator can wait for the next cron tick, or run the script manually with `--force` (which only forces the cheap upstream md5 check; the staleness self-heal runs unconditionally either way).
5. After a successful apply, **auto-reschedule** if `update_interval_days IS NOT NULL`: `next_update_at = now() + update_interval_days`. If null, `next_update_at` is cleared (admin must schedule the next one explicitly).

### Backend

- `PbfFile` entity (`@Entity @Table("pbf_files")`) + `PbfFileRepository`.
- `PbfFileService` — CRUD wrappers + `summarizeWithFreshness()` (rows annotated with derived flags `isStale`, `isApplyInFlight`, `applyAgeMinutes`) + `checkUpstreamNow(pbfName)` (JVM-side `.md5` fetch).
- `AdminPbfController` — endpoints:
  - `GET /api/admin/pbfs` — list, freshness-annotated.
  - `POST /api/admin/pbfs` — create. Body `{pbfName, geofabrikUrl, active, checkIntervalDays, updateIntervalDays, nextCheckAt, nextUpdateAt}`.
  - `PATCH /api/admin/pbfs/{name}` — update mutable fields.
  - `DELETE /api/admin/pbfs/{name}` — drop the row. `routing_coverage.pbf_name` rows pointing here go null via FK.
  - `POST /api/admin/pbfs/{name}/schedule-now` — sets `next_update_at = now()` so the next cron tick processes it.
  - `POST /api/admin/pbfs/{name}/check-md5` — fetches `.md5` from Geofabrik in the calling thread; updates `last_check_at` + `last_remote_*`; does NOT touch `next_check_at`.
  - `POST /api/admin/pbfs/{name}/retry-apply` — clears `last_apply_started_at` (so the row is no longer flagged as in-flight) and sets `next_update_at = now()`. UI surfaces this only when the row is in "apply in flight" state; lets the admin recover from a crashed apply without waiting for the 4 h stale-detection window.

### Frontend

- The ORS Coverage card (with per-region "Trigger" buttons and "Refresh all regions") is **removed** from the Data tab. Polygon refresh is now a backend-only concern, driven by the cron's post-apply step.
- New **Pbfs card** on the Data tab (replaces the coverage card). Shows the pbf_files rows in a table:
  - Name · Geofabrik URL · Active · Last applied (md5 + timestamp) · Last upstream (md5 + Last-Modified) · Stale? · Next check at · Next update at
  - "Schedule now" button per row (sets `next_update_at = now()`).
  - "Check upstream now" button per row (calls the JVM-side md5 fetch).
  - "Retry stuck apply" button — visible only when the row's state is "apply in flight".
  - Toggle Active.
  - Edit URL / check-interval / update-interval / scheduled times via inline form.
  - Add new pbf row, with a confirmation that it's just the orchestration record — the pbf doesn't get processed until the next cron tick that finds `next_update_at` due.
  - Delete row (confirmation modal).

### Migration considerations

- Existing deployments with the old `refreshOrsGraph.sh` and a working us-west extract: the migration auto-seeds the `us-west` row by inspecting `docker/ors-data/` (see "Bootstrap seed" above) and backfills `routing_coverage.pbf_name = 'us-west'` because there's exactly one pbf detected. The operator just runs the migration, then swaps the host crontab from monthly to per-minute. The new script's cheap-check step fills in `last_remote_*` on the next tick. From that point, the old refreshOrsGraph.sh's behaviour is fully replaced.

### Done when

- Admin opens the Data tab and the Pbfs card shows their deployed extract with a freshness state ("up to date as of HH:MM" or "**stale — new pbf available**" with a noticeable colour). They can click "Schedule now" to ask the cron to rebuild on the next minute. Adding a new pbf row creates a `pbf_files` entry that the cron picks up on its next tick.

---

## Phase 2c — Region / pbf collapse

Phase 2b modelled `pbf_files` (orchestration) and `routing_coverage` (dispatcher polygons) as two tables joined by an FK, with one pbf driving many per-state `routing_coverage` rows seeded from the `trip.routing.local-regions` env var. In practice the operator thinks in pbfs: us-west IS the region. Phase 2c collapses to **one `routing_coverage` row per pbf, same name**, drops the env var, and surfaces dispatcher state on the existing Pbfs card — no new card.

### Toggle model

Two independent, manually-set toggles per pbf — the admin's mental model on the card is "this row" but two flags drive different machinery:

| Flag | Table | Controls | Default on create |
|---|---|---|---|
| `pbf_files.active` | `pbf_files` | The cron's processing schedule. `FALSE` = cron skips this row entirely (no md5 check, no apply). | `TRUE` |
| `routing_coverage.enabled` | `routing_coverage` | Whether the dispatcher uses local ORS for points inside this polygon. `FALSE` = dispatcher falls back to public ORS even with a current polygon. Useful for troubleshooting + local-vs-public performance comparisons. | `TRUE` |

Independence is intentional: admin can pause cron auto-updates without taking local routing offline (data stays current); admin can take local routing offline without stopping cron (next cron refresh updates the polygon but doesn't flip routing back on). Polygon-refresh writes `geom` + `fetched_at` only — it never touches `enabled`.

Dispatcher's `coversAll()` filters `WHERE enabled AND geom IS NOT NULL`, so all three conditions (admin enabled routing, polygon fetched at least once, pbf row exists) must hold for local dispatch.

### Schema

Appended to [admin-console-db-migration.sh](dev_scripts/admin-console-db-migration.sh):

```sql
-- Drop the Phase 2b per-state rows. They'll be recreated 1:1 with pbf_files
-- (one routing_coverage row per pbf) by the seed step below, and the cron's
-- next post-apply / staleness self-heal tick fills in the polygon.
DELETE FROM routing_coverage;

-- pbf_name was redundant the moment we collapsed to 1:1; routing_coverage.name
-- IS the pbf name now. Same column with the existing FK constraint.
ALTER TABLE routing_coverage DROP COLUMN pbf_name;

-- Polygon and fetched_at are nullable until the cron fetches the .poly.
-- The dispatcher's coversAll() query is updated to ignore NULL-geom rows.
ALTER TABLE routing_coverage ALTER COLUMN geom DROP NOT NULL;
ALTER TABLE routing_coverage ALTER COLUMN fetched_at DROP NOT NULL;

-- The PK doubles as the FK back to pbf_files. Cascade so admin deleting a
-- pbf row removes its dispatcher entry in the same statement — dispatcher
-- immediately falls back to public ORS for that area.
ALTER TABLE routing_coverage
  ADD CONSTRAINT fk_routing_coverage_pbf
  FOREIGN KEY (name) REFERENCES pbf_files(pbf_name) ON DELETE CASCADE;

-- Seed: one routing_coverage row per pbf_files row. enabled=TRUE matches the
-- "opt-out" default applied to new pbfs created via the admin UI; geom=NULL
-- keeps the dispatcher inert until the cron's next polygon-fetch tick.
INSERT INTO routing_coverage (name, enabled, fetched_at)
SELECT pbf_name, TRUE, NULL FROM pbf_files
ON CONFLICT (name) DO NOTHING;
```

Re-running the migration is safe: `DELETE FROM routing_coverage` only fires on the first run that adds the Phase 2c block (subsequent runs find no per-state rows to drop). The `DROP COLUMN`, `ALTER COLUMN`, and `ADD CONSTRAINT` statements all need `IF NOT EXISTS` / `IF EXISTS` guards — the migration script will use the `DO $$ ... $$` idempotency pattern that the file already uses for similar edits.

### Configuration

`trip.routing.local-regions` (env var `TRIP_ROUTING_LOCAL_REGIONS`) is **removed entirely**:

- [application.properties](src/main/resources/application.properties) — delete the line.
- [GeofabrikCoverageLoader](src/main/java/com/pjr22/tripweather/routing/GeofabrikCoverageLoader.java) — drop the `@Value("${trip.routing.local-regions:colorado}")` injection; remove `getRegions()`; remove `seedMissingRegions()` (the `@EventListener(ApplicationReadyEvent.class)` hook). Fresh installs have empty `routing_coverage`; admin adds a pbf to start covering anything.
- [AdminLoaderService.listLoaders()](src/main/java/com/pjr22/tripweather/service/AdminLoaderService.java) — replace `coverageLoader.getRegions()` with `pbfFileRepository.findAllPbfNames()` (new tiny method) so the Loaders card surfaces one `ors-coverage:{pbfName}` row per pbf.
- `AdminLoaderService.refreshAllCoverageRegions()` — same swap.
- `AdminLoaderService.resolveCoverageWork()` — validate the name is a pbf, not a region.
- `setEnvVariables.source`, `docker/startTripWeather.source`, `CLAUDE.md` env table, `DEPLOYMENT_INSTRUCTIONS.md` (dev + prod env tables) — strip the `TRIP_ROUTING_LOCAL_REGIONS` row.

Polygon URL derivation. `pbf_files.geofabrik_url` ends in `-latest.osm.pbf` by Geofabrik convention; the `.poly` lives one path-level up with no `-latest` suffix. A small helper in `GeofabrikCoverageLoader`:

```java
// us-west-latest.osm.pbf → us-west.poly  (different directory, different basename)
// north-america/us/colorado-latest.osm.pbf → north-america/us/colorado.poly
private static URI derivePolyUrl(String pbfUrl) {
    return URI.create(pbfUrl.replace("-latest.osm.pbf", ".poly"));
}
```

Non-standard URLs would need an explicit `poly_url` column on `pbf_files` — deferred until a deployment hits it.

### Backend

- [RoutingCoverage](src/main/java/com/pjr22/tripweather/model/RoutingCoverage.java) entity: `geom` and `fetchedAt` become `@Nullable`. (Lombok `@Data` already handles the getters/setters.) Phase 2b's `pbfName` field never landed on the entity, so nothing to remove there.
- [RoutingCoverageRepository.coversAll](src/main/java/com/pjr22/tripweather/repository/RoutingCoverageRepository.java#L26) — add `AND geom IS NOT NULL` to the inner SELECT so dispatcher ignores rows whose polygon hasn't been fetched yet. `enabled` already gates.
- `GeofabrikCoverageLoader.refresh(String pbfName, TriggerType)` — keeps the same signature but the argument now identifies a pbf row, not a region from the config. Implementation: load the `PbfFile` row by name (404 if absent), derive the `.poly` URL via the helper above, fetch + parse, upsert `geom` + `fetched_at` only. **Does not touch `enabled`** — that's the admin's manual toggle. The existing `upsert(name, wkt, fetchedAt)` query in `RoutingCoverageRepository` is updated to write only those two columns (drop the `enabled = TRUE` clause). The `IllegalArgumentException` for "not in configured list" goes away.
- [PbfFileService.create()](src/main/java/com/pjr22/tripweather/service/PbfFileService.java) — also inserts the paired `routing_coverage` row in the same transaction: `enabled=TRUE` (opt-out default; admin can flip via PATCH later), `geom=NULL`, `fetched_at=NULL`. Dispatcher's `geom IS NOT NULL` filter keeps it inert until the polygon arrives.
- `PbfFileService.delete()` — no change. FK cascade handles the routing_coverage cleanup.
- [AdminPbfController](src/main/java/com/pjr22/tripweather/controller/AdminPbfController.java) — `PATCH /api/admin/pbfs/{name}` gains an optional `routingEnabled` field. When present, the service writes to `routing_coverage.enabled` in the same transaction as the rest of the PATCH. No new endpoint. Admin's UI sees one row; the controller hides the two-table detail.

### Frontend

The existing Pbfs card on the Data tab becomes the single Routing card. Two display tweaks:

- A new **Routing** column shows dispatcher state:
  - `Active — polygon fetched HH:MM` (enabled=TRUE, geom present) — dispatcher uses local
  - `Disabled — admin paused` (enabled=FALSE, geom present) — dispatcher uses public despite valid data
  - `Awaiting first apply` (geom NULL, regardless of enabled) — no polygon yet, dispatcher uses public
- A per-row toggle button **Disable routing** / **Enable routing** flips `routing_coverage.enabled` via the extended PATCH endpoint. Visible on every row regardless of geom state — admin can pre-disable routing on a new pbf before the polygon even arrives.

The card header subtitle updates from "OSM extracts managed by docker/refreshOrsGraph.sh" to "OSM extracts and their dispatcher coverage. Adding a pbf creates the dispatcher row; the polygon is fetched after the cron's next apply." Other columns and actions (Check, Schedule, Edit, Delete, Add) stay.

### Cron script ([docker/refreshOrsGraph.sh](docker/refreshOrsGraph.sh))

Mechanical updates only — same flow, simpler SQL:

- `do_apply` post-swap polygon-refresh (lines 374-381): the SQL was `SELECT string_agg(name, ',') FROM routing_coverage WHERE pbf_name = '${pbf_name}'`. In the 1:1 model it's `SELECT name FROM routing_coverage WHERE name = '${pbf_name}'` — single row, no comma-aggregation. The loop in `refresh_polygons_for_regions` still works with one item.
- `check_and_refresh_stale_polygons` (lines 394-410): the JOIN `routing_coverage rc JOIN pbf_files pf ON pf.pbf_name = rc.pbf_name` becomes `ON pf.pbf_name = rc.name`. WHERE clause unchanged.
- Error message at line 446 referencing `trip.routing.local-regions` — update to mention the pbf row instead.
- Top-of-file comment at line 16 referencing `routing_coverage.pbf_name` — update to `routing_coverage.name = pbf_files.pbf_name`.

`/api/admin/refresh-coverage/{region}` (the legacy endpoint at [AdminController.java:43](src/main/java/com/pjr22/tripweather/controller/AdminController.java#L43)) keeps the same URL — the `{region}` segment is now the pbf name. Cron continues to call it after a successful apply.

### Tests

- `GeofabrikCoverageLoaderTest` — refactor: `refresh("us-west", ...)` now looks up the pbf row, derives `.poly` URL, fetches, upserts `geom` + `fetched_at` only. Missing pbf row → `IllegalArgumentException` (maps to 404 at the controller). Refresh leaves `enabled` alone (test both starting states). Existing tests that depended on the configured regions list are deleted or rewritten.
- `RoutingCoverageRepositoryTest` — add cases: rows with NULL geom are ignored by `coversAll()`; rows with `enabled=FALSE` are ignored; `upsert()` doesn't change `enabled`.
- `PbfFileServiceTest` — creating a pbf inserts a paired routing_coverage row (`enabled=TRUE`, `geom=NULL`); deleting a pbf cascade-removes it.
- `AdminPbfControllerTest` — PATCH with `routingEnabled=true` flips the dispatcher state; PATCH with `routingEnabled=false` flips it back. PATCH with no `routingEnabled` field leaves it alone.
- `AdminLoaderServiceTest` — listLoaders surfaces one `ors-coverage:{pbfName}` per pbf row.

### Migration considerations

On an existing dev / prod deployment with Phase 2b data (us-west pbf + 11 per-state routing_coverage rows):

1. Operator runs `dev_scripts/admin-console-db-migration.sh` — the Phase 2c block drops the per-state rows and creates one `us-west` row (`enabled=TRUE`, `geom=NULL`).
2. Restart the trip-weather app. Dispatcher sees `coversAll()=false` (geom is NULL) → every routing call goes to public ORS until the polygon is fetched.
3. On the next per-minute cron tick the polygon-staleness self-heal step finds the new row has `fetched_at IS NULL`, fetches `us-west.poly`, upserts `geom` + `fetched_at`. `enabled` was already TRUE from the seed → local routing resumes immediately.

Total local-routing outage: up to ~60 seconds. The dispatcher's public-ORS fallback handles it transparently — no failed routes.

### Done when

- Fresh-install behaviour: empty `routing_coverage`, all routes go to public ORS. Admin adds a pbf via UI, fills in the geofabrik URL, clicks Schedule now. Cron applies the pbf, fetches the polygon, dispatcher routes locally. No env var changes needed.
- Existing deployments: migrate → 60s of public-ORS fallback → local routing back. Admin sees the single `us-west` row on the Pbfs card with the new "Routing: Active" indicator.
- Admin can disable routing without touching pbf processing, and vice versa.
- Deleting a pbf row removes routing coverage immediately via cascade.

### Single-loaded-pbf constraint (addendum)

[runOrs.sh](docker/runOrs.sh) and [ors-config.yml](docker/ors-config.yml) load **one** `.osm.pbf` into the trip-ors container at a time — a single bind-mount at `/home/ors/files/osm-file.osm.pbf`, a single `source_file` in the ORS config. The trip-ors engine doesn't merge multiple extracts and there's no scan-a-directory mode in the official image. So at any moment, only one pbf is actually locally routable, no matter how many `pbf_files` rows exist.

If admin adds `kansas` to a system that already had `us-west`, the next apply for kansas does this:

1. Stops trip-ors.
2. Wipes the `trip_ors_graph` named volume (us-west's CH data — gone).
3. Restarts the container with kansas's pbf mounted.
4. Builds the kansas graph from scratch.

After the apply, **us-west is no longer routable locally**. The dispatcher would still see us-west's `routing_coverage` row (enabled + geom set from the earlier apply) and route US-west requests to local ORS, which would return "no path" because the engine doesn't have that data. Every such request wastes one local round-trip before the public-ORS fallback engages.

**Phase 2c's contract:** after every successful apply (or `NO_CHANGE`) for pbf X, the cron clears `routing_coverage.geom` and `routing_coverage.fetched_at` for every row whose `name != X`. The dispatcher's `coversAll()` filter (`enabled AND geom IS NOT NULL`) then ignores the unloaded rows and the unloaded pbfs fall back to public ORS cleanly. The polygon-staleness self-heal step in the cron is also scoped to the currently-loaded pbf (the row with the most recent successful `last_apply_finished_at`) so it doesn't accidentally re-fetch the cleared polygons.

The other `pbf_files` columns (`last_apply_md5`, `last_apply_finished_at`, etc.) stay populated on the unloaded rows so the admin can see what was there before. The frontend distinguishes three unloaded states for clarity:

- `awaiting first apply` — `geom IS NULL AND last_apply_md5 IS NULL` (truly never applied)
- `not currently loaded — another pbf is the active extract` — `geom IS NULL AND last_apply_md5 IS NOT NULL` (was loaded once; cleared after another pbf was applied)
- `disabled — admin paused` — `geom IS NOT NULL AND enabled = FALSE` (currently loaded but admin opted out of local routing)

The Pbfs card also renders a banner at the top when there are 2+ pbf rows, naming the currently-loaded one explicitly so admin doesn't have to scan rows.

**Merging multiple pbfs is a planned follow-up**, not in this phase. Sketch: pre-apply step on the host runs `osmium merge` over every `pbf_files` row whose `active = TRUE` (or some explicit "include in merged graph" flag), producing a single combined `.osm.pbf` that ORS builds from. Each apply re-merges. Cost: osmium-tool dependency on the host, larger graph build, more heap. Worth doing when admin needs more than one extract live at the same time.

---

## Phase 3 — Metrics dashboard

A curated panel reading directly from `MeterRegistry` plus a "view raw" link.

### Code

- `MetricsSnapshotService` reads:
  - HTTP latency: `http_server_requests_seconds` p50/p95/p99 across all URIs (and a top-5 by count breakdown).
  - Routing dispatch counters: `trip_routing_local_total`, `trip_routing_public_total`, `trip_routing_fallback_total` (whichever exist).
  - JVM heap: `jvm.memory.used{area=heap}`, `jvm.memory.max{area=heap}`.
  - Cache hit ratios: from Caffeine `MeterBinder` for forecast / geocode-forward / ors-directions / ors-snap / ors-elevation. Skip cleanly if a registry is absent (returns `null` for that panel).
- Endpoint: `GET /api/admin/metrics` — JSON snapshot.
- No persistence; every call reads live.

### Frontend

`admin/metrics.html` (and `MetricsView.js`): five panels (HTTP, Routing dispatch, JVM heap, Cache hit ratios, Top URIs by request count). Auto-refresh every 30 s, pausable. Footer link: "View raw → `/actuator/prometheus`".

### Tests

- Empty registry → service returns zeros, not exceptions.
- Known meter names produce expected panel values.
- Hit-ratio computation handles divide-by-zero on a never-accessed cache.

### Done when

- Admin opens the Metrics tab and sees live HTTP latency, routing dispatch breakdown, heap usage, and cache hit ratios without leaving the console.

---

## Phase 4 — User management

List, search, paginate users; enable/disable, force-verify, delete.

### Schema

None. `User.enabled` and the existing `email_verifications` table cover the operations.

### Endpoints

- `GET  /api/admin/users?q=&enabled=&page=&size=&sort=` — paginated list. Returns `{id, email, name, enabled, created, routeCount, hasPendingVerification}`.
- `POST /api/admin/users/{id}/enable` — `enabled=true`.
- `POST /api/admin/users/{id}/disable` — `enabled=false`.
- `POST /api/admin/users/{id}/force-verify` — `enabled=true` AND delete any pending `email_verifications` row for the user.
- `DELETE /api/admin/users/{id}` — hard delete; cascade to routes via existing FK.

### Frontend

`admin/users.html` (and `UsersView.js`): search box (email substring), enabled filter, sortable columns, action buttons. Delete requires double-confirm modal showing email + route count.

### Tests

- Enable/disable round-trip.
- Force-verify clears `email_verifications` row and flips enabled.
- Delete cascades to that user's routes (including soft-deleted ones — they all go).
- Search by email substring; pagination boundaries; sort by `created` DESC default.

### Done when

- Admin can find a user by email, flip their enabled state, force-verify a stuck signup, and delete an account (with double confirmation).

---

## Phase 5 — Hardening (optional)

Land only if the operator hits a real need.

- Login rate limiter on `POST /api/admin/login` — per-IP, 5 attempts / 15 min, in-memory bucket. Reuses the pattern of [RateLimitFilter](src/main/java/com/pjr22/tripweather/filter/RateLimitFilter.java) if it exists, otherwise a small Caffeine cache.
- `admin_audit_log` table — recording each destructive admin action (route delete, route restore, user enable/disable/delete, loader trigger). Schema: `id, admin_username, action, target_type, target_id, payload JSONB, created_at`. Console gets a "Recent admin activity" panel.
- `set-admin-password.sh` helper — BCrypts a value to stdout for the operator to paste into the env. Useful when not wanting plaintext in shell history.
- Self-lockout protection — a banner when the admin user is the *only* admin and tries to delete themselves (n/a today since admin isn't in `users`, but worth revisiting if multi-admin lands).

---

## Risks and open questions

| Item | Mitigation |
|---|---|
| Single `JSESSIONID` shared across chains; misconfiguration of the namespaced security-context attribute could leak `ROLE_ADMIN` to user-chain endpoints. | `AdminAuthControllerTest` covers the attribute-key invariant; smoke-tested live (admin cookie → `/api/auth/me` returns `{"user":null}`). |
| `@SQLRestriction` on `Route` will silently hide soft-deleted rows from any query that doesn't explicitly opt out. Bulk admin operations must use the bypass repo method. | Phase 1 tests cover both directions; the bypass method is the only entry point that returns deleted rows. |
| Hard-delete user with cascade is irreversible. No audit log in v1. | UI double-confirm; phase 5 audit log addresses if it becomes a real concern. |
| Plaintext admin password in the environment is visible to any process running as the same user via `/proc/<pid>/environ`. | Hobby-app threat model; documented. Phase 5 helper for BCrypt-only config is the upgrade path. |
| Existing operator workflow uses `X-Admin-Token` from cron; breaking it would break monthly graph refreshes. | `XAdminTokenAuthenticationFilter` grants only `ROLE_ADMIN_TOKEN`; only `refresh-coverage/{region}` accepts that authority. Verified end-to-end (correct token → 503 from disabled loader, not 401). |

## Files touched, by phase

Phase 0 (landed): `application.properties`, `SecurityConfig`, new `AdminAuthProperties` / `AdminUserDetailsService` / `XAdminTokenAuthenticationFilter` / `AdminAuthController` / `AdminLoginRequest` / `AdminWebMvcConfig` / extended `StartupConfigValidator`; `static/admin/{login.html,index.html,admin.css,js/login.js,js/AdminApp.js,js/api.js}`; `dev_scripts/admin-console-db-migration.sh` (stub); [CLAUDE.md](CLAUDE.md) docs section. Tests: `AdminUserDetailsServiceTest`, `XAdminTokenAuthenticationFilterTest`, `AdminAuthControllerTest`, `StartupConfigValidatorTest`; `AdminControllerTest` rewritten (auth moved to chain); `TripweatherApplicationTests` adds `trip.admin.enabled=false`.

> **Phase 0 deltas from the original plan.** `AdminWebMvcConfig` (forward `/admin/` → `/admin/index.html`, redirect `/admin` → `/admin/`) was added during smoke testing — Spring Boot only resolves directory-index for the application root, not subfolders. `AdminLoginRequest` was added since admin usernames aren't emails. The two-chain split uses an HttpSession **attribute key** namespacing (`SPRING_SECURITY_CONTEXT_ADMIN`) rather than separate `JSESSIONID` cookies as originally sketched — same goal (no role-bleed between chains), simpler mechanism. Token-bearing requests on console-only endpoints return 403 (authenticated as `ROLE_ADMIN_TOKEN` but lacking `ROLE_ADMIN`), not 401 — correct Spring Security semantics, mentioned here so it isn't surprising in access logs.

Phase 1: `Route` entity (`@SQLRestriction` + `deletedAt`), `RouteRepository` (admin native-query method), `GuestRouteCleanupJob`, new `AdminRouteController`; `static/admin/routes.html` + `RoutesView.js`; migration script appends `routes.deleted_at`.

Phase 2: new `LoaderRun` / `LoaderRunRepository` / `LoaderRunRecorder`; wrap `GuestRouteCleanupJob` / `EvStationLoader` / `GeofabrikCoverageLoader`; new `AdminLoaderController`; `static/admin/data.html` + `DataView.js`; migration script appends `loader_runs`.

Phase 3: new `MetricsSnapshotService` / `AdminMetricsController`; `static/admin/metrics.html` + `MetricsView.js`. No schema, no migration entry.

Phase 4: new `AdminUserController`; small additions to `UserAccountService` for force-verify (or call existing pieces); `static/admin/users.html` + `UsersView.js`. No schema, no migration entry.

Phase 5: optional, see above.
