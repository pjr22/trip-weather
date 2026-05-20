# Favorite Waypoints & Route Management — Implementation Plan

Two coupled features for **authenticated users only**: a personal "favorite waypoints" address book, and a "my routes" view for listing / loading / renaming / deleting saved routes. Anonymous (guest) users see no UI change.

## 1. Goal & user story

> *"As a logged-in user I want a one-click way to save a place I plan to revisit (home, a trailhead, a friend's house) so that next time I'm building a route I can drop it in without searching again. And I want one place to see every route I've saved, load any of them back into the editor, rename the ones with bad names, and throw out the ones I no longer need."*

The two features share a common shape — both add user-scoped, soft-deletable resources with a small CRUD API behind authentication, and both add new entry points to the existing profile menu. They're delivered in one plan because the favorites manager and the my-routes manager are sibling modals.

## 2. Decisions (locked)

Captured up front so the rest of the plan reads against fixed constraints.

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | **Favorite is an independent entity** in a single `favorite_waypoints` table with `user_id` FK (not pointer-to-waypoint, not a flag on `waypoints`). | Lifecycle decoupled from routes — deleting a route never orphans favorites; one table for all users with row-level ownership matches `routes`. |
| 2 | **`ON DELETE CASCADE` on `favorite_waypoints.user_id`.** Deleting a user sweeps their favorites the way it already sweeps their routes. | Parity with existing `routes.user_id` FK ([Route.java:60-65](src/main/java/com/pjr22/tripweather/model/Route.java#L60-L65)) and with admin Phase 4 hard-delete semantics. |
| 3 | **Soft-delete (`deleted_at`) for both favorites and routes**, with the same `@SQLRestriction("deleted_at IS NULL")` pattern routes already use. User-initiated delete is recoverable via the admin console (same path admin uses for guest routes today, ADMIN_CONSOLE.md Phase 1). | Single deletion mechanism; admin console gains favorites visibility "for free" in a future mirror; matches the user's preferred semantics. |
| 4 | **Authentication required for every favorites endpoint and every new my-routes endpoint.** No guest fallback. Anonymous callers get 401. | These are personal-account features; the guest user is shared so the concepts don't apply. |
| 5 | **Ownership-checked at the service layer**, not via Spring `@PreAuthorize`. Every read/write resolves the current user from `CurrentUserService.currentUser()` and rejects mismatches with 404 (not 403). | Matches the existing `RoutePersistenceService` pattern ([RoutePersistenceService.java:209-224](src/main/java/com/pjr22/tripweather/service/RoutePersistenceService.java#L209-L224)) — leaks no information about which UUIDs exist. |
| 6 | **`UNIQUE (user_id, label)`** on favorites. A user can't have two favorites with the same label; same label is fine across users. | Forces a meaningful label; avoids a "Home (2)" disambiguation problem in the autocomplete. Migration enforces case-insensitive via `LOWER(label)` unique index. |
| 7 | **"Add as favorite" requires a label.** Default the input to the waypoint's `locationName`; user can edit before confirming. | No silent "favorite with no name" rows; labels are how the user picks one later. |
| 8 | **Heart-toggle initial state = exact (lat,lon,locationName) match** against the user's favorites, evaluated server-side via SQL equality (not a geographic-proximity radius). | Predictable: starring a waypoint and starring the same address clicked a pixel away creates two favorites unless the user chose the same lat/lon from search. Simpler than tuning a radius; revisit only if users complain. |
| 9 | **No frontend cache of favorites. Every read goes to the server.** `FavoritesService.js` is a thin API wrapper, not a session store. Single source of truth in `favorite_waypoints`. | Avoids a parallel data store and the discipline cost of keeping it in sync. Multi-tab divergence becomes impossible. Aligns with the spirit of LOCAL_CACHING_HOSTING.md, where caches exist to absorb **external-API** load — there's no external API here, so there's nothing for a cache to absorb. The same-origin round-trip is ~10-50 ms; the existing forward-geocode autocomplete already calls the server on every debounced keystroke, so adding a parallel `/api/favorites/search` call along the same debounce path is no worse. |
| 10 | **`GET /api/routes` returns the caller's visible routes** as `RouteSummaryDto` (id, name, created, waypointCount, totalDistanceMeters; no waypoint payload). Authenticated → own routes only. Anonymous → guest routes only. No pagination v1. Sort + filter happen client-side. | Symmetrical naming (no `/mine` suffix); access scope is implicit in identity. Per-user / per-guest counts are bounded; easy to add `?page=&size=` later without breaking clients. Loading a full route stays on the existing `GET /api/routes/{uuid}`. |
| 11 | **`GET /api/routes/search/{text}` is folded into `GET /api/routes?search=...`** as part of Phase 4. Old path removed (no external callers; same-origin SPA). | One list endpoint, one shape. Empty/missing `search` returns the full list; non-empty filters server-side by case-insensitive name substring. Existing Load-Route flow's frontend caller migrates in the same change. |
| 12 | **Route "Edit" in the My Routes modal = rename only.** Full editing (add / remove / reorder waypoints) requires Load → modify on the map → Save, same as today. | Matches the user's intent. Avoids duplicating the entire route editor inside the modal. |
| 13 | **`PATCH /api/routes/{id}` is the rename endpoint**; body is `{"name": "..."}` only. The existing `POST /api/routes` (which full-replaces waypoints) is unchanged. | Focused, low-risk. Doesn't perturb the save path that the Save Route button hits every time. |
| 14 | **No new env vars.** Feature is on by default for authenticated users; no Spring properties needed beyond what the existing auth wiring provides. | Nothing to fail-fast on at boot; nothing to forget on a fresh checkout. |
| 15 | **One new migration script `dev_scripts/favorites-db-migration.sh`**, sibling to `user-accounts-db-migration.sh` / `admin-console-db-migration.sh`. Idempotent, single BEGIN/COMMIT. | Matches the established convention (memory: dev_scripts/ exists; user runs, agent only edits). |

## 3. Current state (anchor points in the codebase)

What exists and what's missing.

### Already in place

- **`Route` entity with soft-delete.** [Route.java](src/main/java/com/pjr22/tripweather/model/Route.java) has `@SQLRestriction("deleted_at IS NULL")` ([Route.java:35](src/main/java/com/pjr22/tripweather/model/Route.java#L35)) and `user_id` FK with `ON DELETE CASCADE` ([Route.java:60-65](src/main/java/com/pjr22/tripweather/model/Route.java#L60-L65)). Pattern to mirror.
- **`Waypoint` entity.** [Waypoint.java](src/main/java/com/pjr22/tripweather/model/Waypoint.java) — fields the new `FavoriteWaypoint` mirrors: `locationName`, `latitude`, `longitude`. No `label` concept.
- **`RoutePersistenceService` and controller.** Save / load / search / delete endpoints already exist ([RoutePersistenceController.java](src/main/java/com/pjr22/tripweather/controller/RoutePersistenceController.java), [RoutePersistenceService.java](src/main/java/com/pjr22/tripweather/service/RoutePersistenceService.java)). Phase 4 extends these with `GET /api/routes` (replacing `GET /api/routes/search/{text}`) and `PATCH /api/routes/{id}`.
- **`CurrentUserService`.** [CurrentUserService.java](src/main/java/com/pjr22/tripweather/security/CurrentUserService.java) — `currentUser()` returns the auth'd user or throws; `currentUserOrGuest()` falls back. All new code uses `currentUser()` exclusively.
- **`@SQLRestriction` + native-SQL admin pattern.** [RouteRepository.java:75-123](src/main/java/com/pjr22/tripweather/repository/RouteRepository.java#L75-L123) shows how to bypass the restriction for admin operations. The favorites repository follows the same shape.
- **Profile menu rendering.** [UIManager.js:73](src/main/resources/static/js/managers/UIManager.js#L73) `renderProfileMenu(user)` is the single function that decides which items appear when authenticated vs. anonymous. New "My Favorites" and "My Routes" items hook here.
- **Leaflet control pattern.** [EVChargingStationManager.js:48-85](src/main/resources/static/js/managers/EVChargingStationManager.js#L48-L85) — `L.Control.extend({...})` is the established pattern for a top-left map overlay button. The favorites heart-button overlay reuses it.
- **AuthService change broadcast.** [AuthService.js](src/main/resources/static/js/services/AuthService.js) emits change events that `UIManager` subscribes to ([UIManager.js:62-64](src/main/resources/static/js/managers/UIManager.js#L62-L64)); the favorites-related UI (heart icons, profile-menu items, map-overlay heart button) subscribes the same way to show/hide on login/logout. Per decision #9 there is no favorites session store to populate or clear.
- **Existing rename-route flow.** A rename button already lives in the header at [index.html:59](src/main/resources/static/index.html#L59) and currently rewrites a loaded route's name client-side, then the next save persists it. The My Routes modal's rename action calls the new `PATCH` endpoint directly so it works without loading the route first.
- **Soft-delete cleanup cron.** [GuestRouteCleanupJob.java](src/main/java/com/pjr22/tripweather/cleanup/GuestRouteCleanupJob.java) (and its companions) runs stage 2 (hard-delete past grace) on `routes.deleted_at`. Phase 5 below extends it to favorites.

### Not yet in place

- Any `FavoriteWaypoint` entity, repository, service, controller, DTO, or table.
- Any `/api/favorites/**` endpoints; any rule for them in `SecurityConfig`.
- Any `GET /api/routes/mine` or `PATCH /api/routes/{id}` endpoint.
- Any "My Favorites" / "My Routes" item in the profile menu (placeholder slots exist, but the menu is rendered dynamically).
- Any heart icon on the waypoint popup; any heart-button overlay below the EV Chargers control.
- Frontend `FavoritesService.js`; modal markup for the favorites manager and the my-routes manager.

## 4. Architecture overview

```
Backend (Java)                              Frontend (browser)
──────────────                              ──────────────────
FavoriteWaypoint (entity)  ◄─ new           UIManager.renderProfileMenu()
  @SQLRestriction(...)                        └─ adds "My Favorites" and
  user_id FK CASCADE                              "My Routes" items when
                                                  authenticated, hides them
FavoriteWaypointRepository ◄─ new                 when anonymous
  findByUserIdOrderByLabelAsc, ...
  findByUserIdAndLatitudeAnd                FavoritesService.js  ◄─ new
    LongitudeAndLocationName                  thin API wrapper, no state:
                                              - list(search?)
FavoriteWaypointService    ◄─ new             - check(lat, lon, locationName)
  ownership-checked CRUD                      - add / rename / remove
  + existence check (lat,lon,name)            Each call → server. No Map,
  soft-delete                                 no cache, no event bus.

FavoriteWaypointController ◄─ new           FavoritesManagerModal.js  ◄─ new
  GET    /api/favorites[?search=]             - opens from profile menu or
  GET    /api/favorites/check                   from the map overlay heart
  POST   /api/favorites                       - GET /api/favorites on open
  PUT    /api/favorites/{id}                  - re-renders from each response
  DELETE /api/favorites/{id}                  - "Add to route" appends as
                                                last waypoint of current route
RoutePersistenceController (extended)
  GET  /api/routes[?search=]  ◄─ new        WaypointPopup (existing UI)
       (replaces /search/{text})              + heart icon (auth-only)
  PATCH /api/routes/{id}      ◄─ new           initial state: from waypoint's
  GET  /api/routes/{uuid}                      favoriteId (loaded route) OR
       extended: WaypointDto                    GET /api/favorites/check
       gains nullable favoriteId                (fresh map-click)

                                            WaypointSearchAutocomplete (existing)
                                              + favorites at top (auth-only)
                                              per debounce: parallel call to
                                              GET /api/favorites/search?q=...
                                              alongside the geocode call.

                                            MyRoutesModal.js  ◄─ new
                                              - opens from profile menu
                                              - list/sort/search the user's routes
                                              - Load (delegates to existing flow)
                                              - Rename (PATCH /api/routes/{id})
                                              - Delete (DELETE /api/routes/{id})

SecurityConfig (extended)
  /api/favorites/**         → authenticated
  PATCH /api/routes/{id}    → authenticated
  (GET /api/routes stays open; service layer
   scopes via currentUserOrGuest())
```

Five phases, each shippable on its own. Phases 1-2-3 deliver favorites end-to-end; Phase 4 delivers my-routes; Phase 5 extends the admin console and cleanup cron.

## Phase status

| Phase | Status |
|---|---|
| 1 — Favorites: schema + backend CRUD | Shipped |
| 2 — Favorites: manager modal + entry points | Not started |
| 3 — Favorites: heart toggle in popup + search autocomplete | Not started |
| 4 — My Routes: backend + modal | Not started |
| 5 — Admin console + cleanup cron (favorites) | Not started |

---

## Phase 1 — Favorites: schema + backend CRUD

Stand up the persistence layer and the authenticated CRUD API for `FavoriteWaypoint`. No frontend yet — verified end-to-end with `curl` + the auth session cookie.

### Schema

New idempotent script [dev_scripts/favorites-db-migration.sh](dev_scripts/favorites-db-migration.sh), sibling to `dev_scripts/user-accounts-db-migration.sh`. Single BEGIN/COMMIT; uses the same `TRIP_DB_*` env-var conventions; calls into psql with a here-doc. Contents:

```sql
BEGIN;

CREATE TABLE IF NOT EXISTS favorite_waypoints (
    id              UUID         PRIMARY KEY,
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    label           VARCHAR(255) NOT NULL,
    location_name   VARCHAR(1023) NOT NULL,     -- NOT NULL; service falls back
                                                --   to "lat, lon" (5 decimals)
                                                --   when the request lacks one
    latitude        DOUBLE PRECISION NOT NULL,
    longitude       DOUBLE PRECISION NOT NULL,
    elevation       DOUBLE PRECISION,           -- nullable; mirrors waypoints.elevation
    created         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_favorite_waypoints_user
    ON favorite_waypoints (user_id) WHERE deleted_at IS NULL;

-- Case-insensitive unique label per user, applies only to non-deleted rows
-- so a label freed by soft-delete is reusable.
CREATE UNIQUE INDEX IF NOT EXISTS uq_favorite_waypoints_user_label
    ON favorite_waypoints (user_id, LOWER(label)) WHERE deleted_at IS NULL;

COMMIT;
```

### Entity

`FavoriteWaypoint.java` in `model/`:

```java
@Entity
@Table(name = "favorite_waypoints")
@SQLRestriction("deleted_at IS NULL")
@Data @NoArgsConstructor @AllArgsConstructor
public class FavoriteWaypoint {
    @Id @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(
                    name = "favorite_waypoints_user_id_fkey",
                    foreignKeyDefinition = "FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE"))
    private User user;

    @Column(nullable = false, length = 255) private String label;
    @Column(name = "location_name", length = 1023) private String locationName;
    @Column(nullable = false) private Double latitude;
    @Column(nullable = false) private Double longitude;
    @Column private Double elevation;
    @Column(nullable = false) private ZonedDateTime created;
    @Column(name = "deleted_at") private ZonedDateTime deletedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (created == null) created = ZonedDateTime.now();
    }
}
```

### Repository

`FavoriteWaypointRepository extends JpaRepository<FavoriteWaypoint, UUID>`:

- `List<FavoriteWaypoint> findByUserIdOrderByLabelAsc(UUID userId)` — default alphabetical sort per decision below. Backs `GET /api/favorites` with no query params.
- `List<FavoriteWaypoint> findByUserIdAndLabelOrLocationNameContainingIgnoreCaseOrderByLabelAsc(UUID userId, String text)` — backs `GET /api/favorites?search=...` (label or locationName substring; alphabetical).
- `Optional<FavoriteWaypoint> findByUserIdAndLatitudeAndLongitudeAndLocationName(UUID userId, Double latitude, Double longitude, String locationName)` — backs `GET /api/favorites/check`. Returns at most one row (UNIQUE on label means lat/lon/name could theoretically have duplicates with different labels, but in practice this query returns 0 or 1; pick the first for the heart-toggle answer).
- `Optional<FavoriteWaypoint> findByIdAndUserId(UUID id, UUID userId)`
- `boolean existsByUserIdAndLabelIgnoreCase(UUID userId, String label)`
- Native soft-delete + restore methods (mirror [RouteRepository.java:75-123](src/main/java/com/pjr22/tripweather/repository/RouteRepository.java#L75-L123)) — wired later in Phase 5 when admin console gains a Favorites view.

### Service

`FavoriteWaypointService` — every public method resolves the user via `CurrentUserService.currentUser()` and rejects requests for non-owned ids with `FavoriteNotFoundException` (→ 404). Methods:

- `List<FavoriteWaypointDto> listForCurrentUser(String searchOrNull)` — empty/null search → full alphabetical list; non-empty → label-or-locationName substring filter, alphabetical.
- `Optional<FavoriteWaypointDto> findAt(double latitude, double longitude, String locationName)` — exact-match existence check for the current user. Backs the popup heart-toggle initial state on a fresh map-click.
- `FavoriteWaypointDto create(CreateFavoriteRequest req)` — validates label non-empty, lat/lon present; checks uniqueness; throws `DuplicateFavoriteLabelException` (→ 409) on collision.
- `FavoriteWaypointDto rename(UUID id, String newLabel)` — same uniqueness check; only `label` is mutable. `locationName`/`lat`/`lon` are immutable once saved (a different location = a different favorite).
- `void softDelete(UUID id)` — sets `deleted_at = now()` via JPA save (so `@SQLRestriction` hides it on subsequent reads).

There's deliberately **no batch `findAtMany(...)`** method — for the loaded-route case, the per-waypoint match is performed inside `RoutePersistenceService.loadRoute()` (Phase 3a backend extension) using one JPQL `IN`-clause query, not via this service.

### DTOs

```java
public record FavoriteWaypointDto(
    UUID id, String label, String locationName,
    Double latitude, Double longitude, Double elevation,
    Instant created) {}

public record CreateFavoriteRequest(
    String label, String locationName,
    Double latitude, Double longitude, Double elevation) {}

public record RenameFavoriteRequest(String label) {}
```

`elevation` is nullable in both DTOs; if the source waypoint has it (the normal case after a route's elevation pass), we persist it. If not, the favorite is stored with a null elevation and the next consumer can re-derive it.

### Controller

`FavoriteWaypointController` under `/api/favorites`:

| Method | Path | Auth | Query / Body | Returns |
|---|---|---|---|---|
| GET    | `/api/favorites`                          | authenticated | optional `?search=<text>` | `200 [FavoriteWaypointDto]` (may be empty). With `search`, label-or-locationName substring filter. |
| GET    | `/api/favorites/check`                    | authenticated | `?lat=<d>&lon=<d>&locationName=<s>` | `200 FavoriteWaypointDto` if a match exists, `204` if none. Used by the popup heart-toggle initial state on a fresh map-click. |
| POST   | `/api/favorites`                          | authenticated | `CreateFavoriteRequest` | `201 FavoriteWaypointDto` (or `409` on duplicate label) |
| PUT    | `/api/favorites/{id}`                     | authenticated | `RenameFavoriteRequest` | `200 FavoriteWaypointDto` (or `404` / `409`) |
| DELETE | `/api/favorites/{id}`                     | authenticated | — | `204` (or `404`) |

The check endpoint uses `204 No Content` for "not a favorite" rather than `404` so the client can distinguish "this place isn't starred" from "endpoint or auth broken." `404` stays reserved for ownership / id-not-found errors on the other paths.

### SecurityConfig wiring

In the existing `userSecurityChain` (`SecurityConfig.java`), add:

```java
.requestMatchers("/api/favorites/**").authenticated()
```

CSRF tokens flow on the same cookie + header pair already used by `RoutePersistenceService.js`.

### Tests

- `FavoriteWaypointServiceTest` — list / create / rename / softDelete; ownership rejections; duplicate-label collisions.
- `FavoriteWaypointControllerTest` (Spring `@WebMvcTest` or `@SpringBootTest`) — 401 anonymous, 200/201 happy path, 404 on cross-user access, 409 on duplicate.
- Repository test — verify `@SQLRestriction` hides soft-deleted rows.

**Phase 1 ships when:** migration runs cleanly on a fresh DB and on a populated DB; `curl` against `/api/favorites/**` works with a session cookie; tests pass.

#### Implementation notes (decisions made during build)

- **`location_name` is `NOT NULL`** (tightened from the original draft schema). The service normalises a blank / null request `locationName` to a `"lat, lon"` string with 5-decimal precision (~1.1 m at the equator), so the column always carries something. Rationale: parity with the "no empty names for favorites or routes" principle; the rare map-click-before-reverse-geocode case is absorbed server-side without surfacing a fallback path to the rest of the UI.
- **`FavoriteWaypointDto.created` is `ZonedDateTime`** (not `Instant` as the planning sketch showed) — matches `RouteDto` / `RouteSearchResultDto` so the SPA's existing date-rendering helpers work unchanged.
- **`?search=` is implemented as an explicit JPQL `@Query`** rather than the planning sketch's derived-name method, because a Spring Data derived name like `findByUserIdAndLabelOrLocationNameContainingIgnoreCase…` parses as `userId=? AND label=? OR locationName ILIKE ?` — the OR escapes the userId scope and matches favorites across all users. The `@Query` enforces `userId=? AND (label ILIKE ? OR locationName ILIKE ?)` unambiguously.
- **400 path:** in addition to the planned `FavoriteNotFoundException` (404) and `DuplicateFavoriteLabelException` (409), the service throws `InvalidFavoriteException` (400) for blank label, missing latitude/longitude, or over-length label. All three exception classes are nested in `FavoriteWaypointService` and carry `@ResponseStatus`, matching the `RoutePersistenceService` pattern.

---

## Phase 2 — Favorites: manager modal + entry points

Frontend-only phase. Wire two entry points to one modal.

### Entry points

1. **Profile menu item "My Favorites"** — extend [UIManager.js:73](src/main/resources/static/js/managers/UIManager.js#L73) `renderProfileMenu(user)` to add the item only when `user != null`. Sits between "My Routes" (added in Phase 4) and "Preferences". The action handler in `UIManager.handleProfileAction()` opens the modal.

2. **Heart-button overlay on the map.** New Leaflet control under [EVChargingStationManager.js:48-85](src/main/resources/static/js/managers/EVChargingStationManager.js#L48-L85) (a sibling `FavoritesOverlayControl`). Added to the `topleft` position so it stacks below the EV Chargers button. Hidden when `AuthService.getCurrentUser()` is null; shown when authenticated; re-evaluated on `AuthService.onChange`. Icon: SVG heart matching the style of the existing buttons. Click → same modal as the profile-menu item.

### `FavoritesService.js`

New file under `static/js/services/`. **Stateless** — a thin wrapper over `/api/favorites/*`. No `Map`, no event bus, no AuthService subscription. Methods:

- `list(searchOrNull)` → `Promise<FavoriteWaypoint[]>` — calls `GET /api/favorites` (with `?search=...` if provided).
- `check({latitude, longitude, locationName})` → `Promise<FavoriteWaypoint | null>` — calls `GET /api/favorites/check`; resolves `null` on `204`.
- `add({label, locationName, latitude, longitude, elevation})` → `Promise<FavoriteWaypoint>` — `POST`; rejects on `409` with a typed `DuplicateFavoriteLabel` error so callers can surface a useful message.
- `rename(id, label)` → `Promise<FavoriteWaypoint>` — `PUT /api/favorites/{id}`.
- `remove(id)` → `Promise<void>` — `DELETE /api/favorites/{id}`.

Per decision #9, each call goes back to the server; no in-browser state survives between calls. Components that need a current snapshot (the manager modal, an open waypoint popup) hold their own local rendered state for the lifetime of that component and re-fetch on subsequent opens.

### `FavoritesManagerModal.js`

Single modal with one view (no tabs). On open, calls `FavoritesService.list()` and renders the response into a local array. Mutating actions (add / rename / delete) hit the server, then patch the local array from the response body — no cross-component event bus needed because the modal owns the only rendered list. **Default sort: alphabetical by label** (case-insensitive) — both server-side and within the modal. Other sort options deferred (see D3).

```
┌─ My Favorites ────────────────────────────── ✕ ─┐
│ Search: [_____________]                          │
├───────────┬──────────────────┬─────────────┬───────────┤
│ Label     │ Address          │ Lat,Lon     │ Actions   │
├───────────┼──────────────────┼─────────────┼───────────┤
│ Home      │ 1234 Elm St ...  │ 39.7,-105.0 │ Add | ✎ | 🗑 │
│ Trailhead │ Bear Peak ...    │ 39.9,-105.3 │ Add | ✎ | 🗑 │
└───────────┴──────────────────┴─────────────┴───────────┘
```

Row actions:

- **Add (to current route)** — appends the favorite as the next waypoint of the route currently in the editor. Uses existing `RouteManager.addWaypoint({...})` so the timing column gets recomputed.
- **Rename (✎)** — inline rename: row collapses to an input + Save / Cancel buttons; `PUT /api/favorites/{id}`.
- **Delete (🗑)** — confirms in a small inline prompt ("Delete *Home*? It can be restored from the admin console for 7 days."), then `DELETE /api/favorites/{id}`. Toast confirms.

Reuse the shared `Toast` utility at [static/js/utils/Toast.js](src/main/resources/static/js/utils/Toast.js) (extracted by ADMIN_CONSOLE Phase 2c).

### CSS

- New section in `static/css/styles.css` (or a new `favorites.css` linked from `index.html`) for the modal and the overlay button.
- The heart-button overlay reuses the existing leaflet-bar control sizing so it stacks visually with the EV Chargers button.

### Tests

- Manual smoke: signup → log in → open modal → add a stub favorite via the admin console (or directly via `curl` until Phase 3 ships the heart toggle) → see it in the modal → add to route → rename → delete → confirm soft-deleted in DB.

**Phase 2 ships when:** clicking the heart-overlay or the menu item opens the modal; favorites already in the DB render; "Add to route", Rename, Delete each function end-to-end; the overlay hides for anonymous users.

---

## Phase 3 — Favorites: heart toggle in popup + search autocomplete

Add the two in-context affordances that close the loop on "make and reuse favorites without leaving the map": the heart toggle on the waypoint popup, and surfacing of favorites in the waypoint search dropdown.

### 3a. Heart toggle in the waypoint popup

This phase has a **small backend extension in addition to the frontend work**: the route load response gains a per-waypoint `favoriteId`.

#### Backend extension

`WaypointDto` (the existing DTO returned by `GET /api/routes/{uuid}`) gains a nullable `UUID favoriteId` field. `RoutePersistenceService.loadRoute()` populates it server-side: after fetching the route, it issues one query against `favorite_waypoints` for the **viewer's** user id, matching any waypoint's `(latitude, longitude, locationName)` via a JPQL `IN`-tuple (or equivalent). For each match, the corresponding `WaypointDto.favoriteId` is set. For anonymous viewers, or for waypoints with no match, the field stays `null`.

This means a shared-link viewer (looking at someone else's route) sees `favoriteId` reflecting **their own** favorites, not the route owner's — the right behavior, since the heart is "is this place starred by *me*?".

#### UI

- Add a heart icon to the waypoint popup template (locate during implementation — `static/js/managers/WaypointManager.js` is the likely host). Position: upper-right corner of the popup if width allows; otherwise lower-right.
- **Hidden for anonymous users** (`AuthService.getCurrentUser() == null`). No CTA to log in from here — the profile menu is the path.
- **Outline heart** when the waypoint is not a favorite. **Filled red heart** when it is. Inline SVG so the toggle is cheap and theme-aware.

#### Initial-state resolution

- **Waypoint from a freshly loaded route**: the `favoriteId` on the waypoint object is the answer. No HTTP call. Heart renders filled (if non-null) or outline (if null) immediately.
- **Waypoint from a fresh map-click or search-add** (no server round-trip carried a `favoriteId`): on first popup open, the popup calls `FavoritesService.check({latitude, longitude, locationName})` once and renders the result. The answer lives only on the popup component for that popup's lifetime — closing and reopening the popup re-checks, which absorbs any change made elsewhere (including from another tab).

#### Toggle behavior

- Click outline → small inline prompt above/below the heart: label input pre-filled with the waypoint's `locationName`, Save button. Save → `FavoritesService.add({label, locationName, latitude, longitude, elevation})`. On success the popup updates its local heart state from the response. On `409 duplicate label` the input shows an error and stays open.
- Click filled → confirm-prompt ("Remove *Home* from favorites?"), then `FavoritesService.remove(id)`. Heart returns to outline.
- No cross-component event needed. The manager modal, if open in the same tab, will see the change on its next open (it re-fetches). If both are open at once, the modal won't reflect the popup's edit until reopened — acceptable per decision #9; both are user-driven, neither is a continuous view.

#### Edge cases

- **Favorite that no longer matches any current waypoint:** still appears in the manager modal; user can use Add-to-route from there. The waypoint popup just renders the outline heart.
- **Same lat/lon but the user clicked through search and the `locationName` differs slightly:** treated as a separate place per decision #8. Documented in the deferred section below.
- **Waypoint without a `locationName`** (rare — map-click without reverse geocode): heart still works, label defaults to a coordinate string like `"39.7402, -105.0234"`. The `check` query passes `locationName=` empty; the matching favorite (if any) must also have an empty `locationName` to match.

#### Tests

- Backend: `WaypointDto.favoriteId` is populated for the viewer's matching favorites on `GET /api/routes/{uuid}`; null for anonymous; null when no match; reflects the *viewer's* favorites, not the route owner's, on a shared link.
- Frontend manual smoke: click a fresh waypoint → popup calls `/check` → outline → click → enter label → filled. Reload route → filled (from `favoriteId`, no `/check` call). Remove favorite → outline on next popup open. Log out → heart vanishes.

### 3b. Favorites in the waypoint search dropdown

The existing waypoint search box ([LocationService.js](src/main/resources/static/js/services/LocationService.js) + its dropdown renderer) calls the forward-geocode service as the user types. Augment the dropdown so matching favorites surface **at the top**, above the geocode results, each prefixed with a filled-red heart icon to make their provenance obvious.

#### Behavior

- **Auth gating** — favorites only appear when `AuthService.getCurrentUser() != null`. Anonymous users see exactly today's geocode-only dropdown.
- **Per-debounce fetch** — on each debounced keystroke (the same debounce that fires the forward-geocode call), fire a parallel `GET /api/favorites?search=<q>` and merge the results into the dropdown above the geocode results. Server-side substring match against `label` and `locationName`, alphabetical.
- **Layout** — favorites section appears first, with a thin `Favorites` separator label and the heart-prefixed rows. Geocode results follow below under their existing rendering. If no favorites match (or the user is anonymous), the dropdown looks exactly as it does today.
- **Selection** — clicking a favorite has the same effect as the manager modal's "Add to route" action: append as the next waypoint of the current route, using the favorite's `locationName`, `latitude`, `longitude`, and `elevation` from the response.
- **Empty input** — when the search box is empty, no `/api/favorites?search=` call fires (same as today's geocode behavior). Browsing the full list happens via the manager modal.
- **Cost** — one extra same-origin round-trip per debounce, in parallel with the forward-geocode call (no added wall-clock latency). Tens-of-rows payload, ~1 KB. Negligible.

#### Tests

- Manual smoke: with no favorites, dropdown behaves as today. Add a favorite "Home" → typing `ho` → "Home" appears at the top with heart icon → click → it becomes the next waypoint. Add a favorite while the dropdown is open in another tab → next debounce in this tab picks up the new row (server-driven freshness, no client invalidation needed). Log out → typing `ho` shows only geocode results.

**Phase 3 ships when:** the popup heart toggle works on freshly-clicked waypoints and on loaded-route waypoints (using `WaypointDto.favoriteId` and the `/check` endpoint respectively); the search dropdown surfaces matching favorites at the top for authenticated users on every debounced keystroke; logout hides both affordances; no frontend favorites cache exists.

---

## Phase 4 — My Routes: backend + modal

The route-management half. Backend additions are small; most of the work is the modal.

### Backend

**`GET /api/routes`** — list the caller's visible routes. **Replaces `GET /api/routes/search/{text}`.**

- **Anonymous → guest user's routes; authenticated → caller's own routes.** Service uses `CurrentUserService.currentUserOrGuest()` to resolve the owner; the controller is `permitAll` (the user chain doesn't add a rule for it).
- Optional query param `?search=<text>` — case-insensitive name substring filter, server-side (reuses the existing `findByUserIdAndNameContainingIgnoreCase` query at [RouteRepository.java:33-57](src/main/java/com/pjr22/tripweather/repository/RouteRepository.java#L33-L57)). Missing / empty → unfiltered list.
- Returns `List<RouteSummaryDto>` sorted by `created DESC` (server-side default; the modal can re-sort client-side):
  ```java
  public record RouteSummaryDto(
      UUID id, String name, Instant created,
      int waypointCount, Double totalDistanceMeters /* nullable */) {}
  ```
- `waypointCount` from a JPQL `COUNT(w) GROUP BY w.route.id` query so we don't hydrate the whole graph just to count.
- `totalDistanceMeters` — pulled from whatever cached field is already on the route summary; nullable if not computed yet.
- **Migrating the old endpoint**: `GET /api/routes/search/{text}` is removed in the same change. The one frontend caller in [RoutePersistenceService.js](src/main/resources/static/js/services/RoutePersistenceService.js) (used by the Load Route UI) is updated to call `GET /api/routes?search=...`. Since today's search returned full routes with waypoints and the new endpoint returns summaries, the Load Route UI's click-to-load handler now does a follow-up `GET /api/routes/{uuid}` to load the full route — one extra request per loaded route, acceptable tradeoff for one consistent shape across the list endpoint. Same-origin SPA = no external callers to worry about.

**`PATCH /api/routes/{id}`** — rename only.

- Auth required. Body: `{"name": "..."}`. Validates non-empty + length ≤ 255.
- Ownership check identical to the existing `DELETE` flow at [RoutePersistenceService.java:209-224](src/main/java/com/pjr22/tripweather/service/RoutePersistenceService.java#L209-L224).
- Returns `200 RouteSummaryDto`. 404 on non-existent or non-owned.
- Does not touch waypoints, ordering, or any other field.

### SecurityConfig wiring

```java
.requestMatchers(HttpMethod.PATCH, "/api/routes/*").authenticated()
```

`GET /api/routes` stays open (scoped at the service layer); the removed `/api/routes/search/**` rule (if any explicit one existed) is dropped. The existing `POST /api/routes`, `GET /api/routes/{id}`, `DELETE /api/routes/{id}` rules stay as they are.

### Frontend

**Profile menu item "My Routes"** — same wiring as "My Favorites" in Phase 2.

**`MyRoutesModal.js`** — opened from the profile menu.

```
┌─ My Routes ────────────────────────────────────── ✕ ─┐
│ Search: [_____________]   Sort: [Created ▼]           │
├───────────────────┬───────────┬───────────┬───────────┬─────────────────────┤
│ Name              │ Created   │ Waypoints │ Distance  │ Actions             │
├───────────────────┼───────────┼───────────┼───────────┼─────────────────────┤
│ Boulder weekend   │ May 10    │     4     │ 72 mi     │ Load | Rename | 🗑  │
│ Ouray loop        │ May 03    │     7     │ 184 mi    │ Load | Rename | 🗑  │
└───────────────────┴───────────┴───────────┴───────────┴─────────────────────┘
```

- On open, modal fetches `GET /api/routes` (no query param) and renders all rows. Search and Sort happen **client-side** over the in-memory list — the server-side `?search=` is reserved for the (separate) Load Route flow and future pagination.
- **Load** — invokes the existing route-load flow (same code path the "Load Route" button uses, [RouteManager.js](src/main/resources/static/js/managers/RouteManager.js) — locate the entry point during implementation), then closes the modal.
- **Rename** — inline editor on the row, `PATCH /api/routes/{id}`. Updates the list in place. If the renamed route is the one currently loaded in the editor, the header's "Route: ..." display ([index.html:62](src/main/resources/static/index.html#L62)) updates too.
- **Delete (🗑)** — confirm ("Delete *Boulder weekend*? It can be restored from the admin console for 7 days."), then `DELETE /api/routes/{id}`. Row vanishes from the list. If the deleted route is the one currently loaded, the editor clears to a new-route state.

### Tests

- Backend: `GET /api/routes` returns only the caller's routes (own when auth'd, guest's when anonymous); `?search=...` filters by name substring; `PATCH` rejects non-owned with 404; `PATCH` validates name length; the removed `/api/routes/search/{text}` returns 404.
- Frontend manual smoke: open modal → see all saved routes → load one → re-open → rename → re-open → delete → confirm gone → confirm soft-deleted in DB. Also verify the existing Load Route UI still works after migrating to `?search=`.

**Phase 4 ships when:** the My Routes menu item works; Load / Rename / Delete each function end-to-end; the modal updates in place after each action; deleted routes disappear and stay invisible after refresh.

---

## Phase 5 — Admin console + cleanup cron (favorites)

Optional polish phase. Brings favorites under the existing operator controls so the soft-delete promise made to users in Phases 2 and 4 is actually backed by a recovery path and a stage-2 purge.

### Admin console

New `AdminFavoriteController` + `AdminFavoriteService`, modelled exactly on [AdminRouteController](src/main/java/com/pjr22/tripweather/admin/controller/AdminRouteController.java) and `AdminRouteService` from ADMIN_CONSOLE.md Phase 1. Endpoints (under `/api/admin/favorites/**`, secured by `adminSecurityChain`):

| Method | Path | Purpose |
|---|---|---|
| GET    | `/api/admin/favorites` | Paginated list — substring search on owner email + on label; `?showDeleted=true` includes soft-deleted rows. |
| POST   | `/api/admin/favorites/{id}/soft-delete` | Admin-initiated soft-delete. |
| POST   | `/api/admin/favorites/{id}/restore` | Restore (clear `deleted_at`). Idempotent. |
| DELETE | `/api/admin/favorites/{id}` | Hard delete, native SQL. |

Native-SQL admin paths bypass `@SQLRestriction` exactly the way `RouteRepository.adminSoftDelete` / `adminRestore` / `softDeleteGuestRoutesCreatedBefore` do today.

New admin SPA view `FavoritesView.js` under `src/main/resources/static/admin/js/managers/`, modelled on `RoutesView.js`. Added to the left-nav shell.

### Cleanup cron

Extend the existing scheduled cleanup job ([GuestRouteCleanupJob.java](src/main/java/com/pjr22/tripweather/cleanup/GuestRouteCleanupJob.java)) — stage 2 hard-deletes any soft-deleted `favorite_waypoints` row past the existing `ROUTE_CLEANUP_PURGE_GRACE_DAYS` window. Reuse the same env var (favorites are recoverable on the same admin-window as routes). Stage 1 (guest-route soft-delete) is unaffected — guest users don't own favorites.

### Tests

- Mirror `AdminRouteServiceTest` for favorites.
- Cleanup test: a soft-deleted favorite older than the grace window is hard-deleted; a newer one survives.

**Phase 5 ships when:** the admin console's Favorites view supports search / show-deleted / restore / soft-delete / hard-delete; the cleanup cron hard-deletes aged-out soft-deleted favorites; tests pass.

---

## 5. Deferred / open questions

Items called out during planning that we chose not to address in v1. Revisit if the v1 ergonomics fall short.

| # | Item | Status | Notes |
|---|------|--------|-------|
| D1 | ~~Favorites in the waypoint search-box autocomplete.~~ | **Moved into Phase 3b.** | Resolved during plan review — favorites surface at the top of the existing search dropdown with a filled-heart prefix, auth-gated, alphabetical. |
| D2 | **Geographic-proximity match for the heart-toggle initial state** (instead of exact equality, decision #8). | Deferred. | If users complain that re-clicking the same place doesn't show as a favorite, switch to `ST_DWithin` (~25 m) — cheap to add later, costly to roll back if it produces false-positive matches. |
| D3 | **Additional sort options for the favorites manager modal** (created-date, last-used, drag-to-reorder). | Deferred. | v1 default is alphabetical by label. Drag-to-reorder would need a `display_order` column. Revisit when users ask. |
| D4 | **Bulk delete in My Routes.** | Deferred (not v1). | Single-row delete handles the common case. Add a multi-select header checkbox + bulk-delete button only if users accumulate enough cruft to need it. |
| D5 | **Pagination on `GET /api/routes` and `GET /api/favorites`.** | Deferred (not v1). | Skipped per decisions #9, #10. Endpoint shape can be extended with `?page=&size=` without breaking existing clients. |
| D6 | **"My Routes" full edit in the modal** (add / remove / reorder waypoints without round-tripping through the map editor). | Out of scope (confirmed). | Per decision #12. Not on the roadmap. |
| D7 | **Tags / categories on favorites.** | **Planned for v2.** | A flat list with substring search is enough for tens of favorites today. Tag support is a separate feature that will get its own plan. |
| D8 | **Share a favorite via link.** | Deferred (not v1). | Routes can be shared by UUID today; favorites can't. Single coordinate + label is easier to send by SMS than to design a share-link flow for. |
| D9 | **Codebase-wide `Lng` → `Lon` (or `Longitude`) rename.** | Backlog (separate cleanup). | Noticed during plan review. `Lng` is non-canonical; preferred shortening is `Lon`. Touches identifiers, comments, UI strings, and possibly column names if any exist. Not part of this feature, but tracked here so it doesn't get lost. Worth running once as a focused refactor PR. |
