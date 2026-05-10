# Admin Console — design and phased plan

A separate `/admin` SPA, gated by a single admin credential supplied via Spring properties (and overridable by environment variables). Five phases, each shippable on its own.

## Phase status

| Phase | Status |
|---|---|
| 0 — Authentication & shell | **Done** (2026-05-10). Two-chain SecurityConfig + namespaced session attribute, JSON login/logout/me, X-Admin-Token coexists on `refresh-coverage` only, login → empty shell. Smoke-tested live; 161/161 unit tests pass. |
| 1 — Route management | Not started. |
| 2 — Loader & data management | Not started. |
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
