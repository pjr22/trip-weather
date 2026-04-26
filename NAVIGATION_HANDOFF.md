# Navigation Feature — Handoff

Picking up the navigation feature from another machine. Pair this with [NAVIGATION_PLAN.md](NAVIGATION_PLAN.md) — the plan is the spec, this is the in-flight status.

## Status at handoff

**Phase 1** (backend instructions) and **Phase 2** (navigation engine, voice, simulated GPS) are complete and tested. **Phase 3a** (filter ORS waypoint marker steps + build `waypointStops[]` side-data) is complete; **3b–3e** are pending.

The user can plan/load a route, click Navigate, and either drive it (live GPS) or simulate it (`?simgps=1`). Voice prompts fire at the FAR/MID/NEAR/NOW buckets for real maneuvers; passthrough waypoints (duration == 0) are silent; the polyline-end check announces final arrival. The simulator slows down within 1 mi of each maneuver (real-time) and within 0.25 mi (½ real-time) so prompts are audible during desk testing.

**Next concrete step:** Phase 3b — connector route on Navigate (snap user → compute connector via `/api/route/calculate` → splice in front of saved-route maneuvers → drop on join). See §6.5 of NAVIGATION_PLAN.md.

## What works today (verification checklist)

After resuming, smoke-test these to confirm nothing regressed:

1. `./gradlew bootRun` starts on `localhost:8090`.
2. Plan a multi-waypoint route, click **🚗 Calculate Route** — polyline appears, the **🧭 Navigate** button enables.
3. Click Navigate — banner appears, map goes full-screen, voice says "Starting navigation."
4. With `?simgps=1` on the URL, the dot walks along the route and voice prompts fire at the FAR/MID/NEAR/NOW thresholds for each turn. Passthrough waypoints (duration 0) are silent.
5. On reaching the final waypoint, voice says "You have arrived at your destination" and the nav UI exits.
6. **Exit Navigation** button works mid-route.
7. Try `?simgps=1&simspeed=20` — fast cruise between turns, slows to real-time within 1 mi of each turn, ½ real-time within 0.25 mi.

## Resume instructions

```bash
source setEnvVariables.source         # OPENROUTESERVICE / GEOAPIFY / NREL keys
export TRIP_DB_PASSWORD='<password>'  # required, app fails fast without it
./gradlew bootRun                     # localhost:8090
```

JDK 21 toolchain, auto-provisioned by the Gradle wrapper. The three `*_api_key.txt` files are gitignored — copy them across when moving machines, or get fresh keys from each provider.

Frontend has no build step. JS/CSS edits are picked up on browser refresh.

## Phase progress

| Phase | Scope                                                                  | Status |
|------:|------------------------------------------------------------------------|--------|
|     1 | Backend: request `instructions: true` from ORS, parse `steps[]`        | ✅ done |
|     2 | Engine, voice, simulated GPS, nav-mode UI, wake-lock                   | ✅ done |
|    3a | Filter ORS type-10/11 marker steps; build `waypointStops[]`            | ✅ done |
|    3b | Connector route on Navigate (snap → compute → splice → join)           | ⏳ next |
|    3c | Off-route detection + guide-back; 20s cooldown                         | ⏳      |
|    3d | `duration > 0` pause/Continue + arrival announcements                  | ⏳      |
|    3e | Skip button + drive-past handling (re-route targets the waypoint)      | ⏳      |
|     4 | Mobile-first planning view + PWA shell                                 | ⏳      |
|     5 | v2 candidates: MapLibre alt, hybrid off-route, Settings                | future |

## Phase 3 — what each remaining sub-phase needs to build

Order matters: 3b/3c build the connector machinery that 3d/3e depend on.

### 3b — Connector route on Navigate (§6.5)

On clicking **Navigate**:

1. Get a single high-accuracy fix via `getCurrentPosition` (not `watchPosition` yet).
2. Snap to nearest point on saved polyline using the existing `RouteSnapper`.
3. If `snap.crossTrackM <= ON_ROUTE_THRESHOLD_M` (30 m): start nav from the snapped position, no connector.
4. Otherwise call `POST /api/route/calculate` with `[currentLocation, nearestPoint]` to get a real driving connector (with its own `steps[]`).
5. Render the connector polyline in **dashed orange** (style hook needed in `NavMapAdapter` or `RouteManager`).
6. Splice: the active maneuver list = `connector.steps[] + savedRoute.steps[fromForwardIdx..]`.
7. When the user reaches the join point (within `ON_ROUTE_THRESHOLD_M`), drop the connector polyline; saved-route polyline returns to normal blue. The maneuver list is already past the connector entries by then — no special action needed.

For `?simgps=1` mode: skip the connector (the simulator IS the route), start at distance 0 as today.

If `getCurrentPosition` is denied or times out → toast + abort. No fallback.

### 3c — Off-route detection + guide-back (§6.3)

In `_onPosition`, track cross-track distance. Off-route fires when `crossTrackM > OFF_ROUTE_THRESHOLD_M` (50 m) sustained for `OFF_ROUTE_SUSTAINED_MS` (10 s).

On off-route:
1. Cancel current voice prompt; say "Re-routing."
2. Find nearest **forward** point on the polyline (search from `lastSegmentIdx`, not from start — otherwise you can match a closer earlier point and send the user backwards through stops they've done).
3. Call `/api/route/calculate` with `[currentLocation, forwardPoint]`.
4. Splice connector steps in front of `savedRoute.steps[fromForwardIdx..]`, render dashed-orange connector polyline.
5. When user reaches join point, drop connector polyline.
6. Apply `REROUTE_COOLDOWN_MS` (20 s) before another re-route can fire. If the API call fails, keep the original route and notify; don't retry.

Constants are already in [NavigationConstants.js](src/main/resources/static/js/nav/NavigationConstants.js).

### 3d — Pause/Continue at duration > 0 waypoints (§6.7)

Now that `waypointStops[]` exists (3a), add an arrival-watcher in `_onPosition` that walks the not-yet-arrived `waypointStops[]` and checks whether the user's snapped position is within `ARRIVAL_RADIUS_M` (50 m) of each.

On approach to a `duration > 0` waypoint, fire FAR/MID/NEAR/NOW announcements with phrasing "Arriving at [name] in half a mile" → "You have arrived at [name]." (May want a separate `ManeuverScheduler` instance for waypoint approach prompts, or extend the existing one.)

On arrival within `ARRIVAL_RADIUS_M`:
- Set state to **paused at waypoint i**.
- Suppress voice (`VoiceGuide.setEnabled(false)`).
- Suppress off-route detection.
- Show **Continue** button in the banner ("Stopped at [name] — tap Continue when ready").

On Continue:
- Re-enable voice + off-route.
- Resume from the next maneuver after this waypoint (find by polyline index).
- If user position is no longer within `ON_ROUTE_THRESHOLD_M`, the standard guide-back from 3c kicks in immediately.

`duration == 0` waypoints: no behaviour at all (passthroughs).

**Final waypoint** always ends the session — already handled by the polyline-end check; don't apply pause logic to it (use `stop.isFinal`).

### 3e — Skip button + drive-past handling (§6.7)

Driving past a `duration > 0` waypoint geometrically (without arriving) does **not** count as done. Re-route the user back to the waypoint using 3c's machinery, but with the **target = the waypoint itself**, not the polyline forward point.

Skip button visibility:
- Appears when distance to the waypoint first drops below `SKIP_AVAILABLE_DISTANCE_M` (~2 mi).
- Once shown, **stays visible** until tapped or arrival within `ARRIVAL_RADIUS_M`. Includes the entire approach + drive-past + re-route phase. Otherwise the user has no escape from the loop.

On Skip:
- Mark waypoint as skipped.
- Trigger guide-back to the **nearest forward point on the saved polyline past the skipped waypoint** (start search at `stop.polylineIdx + 1`).
- Resume original instructions from there.

Off-route detection (3c) stays active throughout (including while routing back to a missed waypoint). The 20 s cooldown limits API churn even if the user keeps driving away.

## Files created in this work

```
NAVIGATION_PLAN.md                                       (the spec — open questions all resolved)
NAVIGATION_HANDOFF.md                                    (this file)

src/main/java/com/pjr22/tripweather/
  model/RouteData.java                                   (added nested RouteStep class; segment.steps now typed)
  service/RouteService.java                              (instructions:true, parseSteps helper, language)

src/main/resources/static/
  index.html                                             (Navigate button, nav.css link, nav script tags)
  css/nav.css                                            (NEW — body.nav-mode, banner, exit, position arrow)
  js/app.js                                              (registers Navigation manager)
  js/managers/NavigationManager.js                       (NEW — orchestrator)
  js/managers/RouteManager.js                            (currentRoute set inside displayRoute; notifies Navigation)
  js/nav/NavigationConstants.js                          (NEW — all distance/time thresholds in one place)
  js/nav/WakeLock.js                                     (NEW — screen wake-lock with re-acquire on visibilitychange)
  js/nav/VoiceGuide.js                                   (NEW — Web Speech wrapper; iOS-gesture unlock; cancel-on-say)
  js/nav/PositionSource.js                               (NEW — Live + Playback (with maneuver-aware slowdown))
  js/nav/RouteSnapper.js                                 (NEW — compile + snap with forward-window + fallback)
  js/nav/NavMapAdapter.js                                (NEW — Leaflet adapter; engine-agnostic interface)
  js/nav/ManeuverScheduler.js                            (NEW — FAR/MID/NEAR/NOW buckets, dedup, bunching)
```

The frontend follows the existing namespace pattern: utilities at `window.TripWeather.Nav.*`, the orchestrator at `window.TripWeather.Managers.Navigation`. Script load order in `index.html` matters — nav helpers load before `NavigationManager.js`, which loads before `app.js`.

## Decisions locked in (full rationale in NAVIGATION_PLAN.md §10)

All ten open questions from the plan are resolved. Cliff notes:

- **Off-route strategy**: guide-back-to-route (not full re-plan). Hybrid fallback deferred to v2.
- **Waypoint semantics**: `duration > 0` = mandatory destination (arrive or Skip); `duration == 0` = silent passthrough.
- **Skip behaviour**: button appears at 2 mi from a `duration > 0` waypoint, persists until tapped or arrived. On Skip → guide-back past the waypoint.
- **Pause behaviour**: open-ended; resume only on Continue or route reload. Off-route detection re-engages on Continue (covers "moved the car").
- **Voice/units/language**: hardcoded for v1 (mi/ft, system `en-US`, English ORS). Constants centralised in `NavigationConstants.js`.
- **Backgrounded use**: out of scope. v1 = mounted phone, screen on, app foregrounded. Wake-lock keeps the screen alive.
- **Map rotation**: north-up Leaflet for v1. Phase 5 adds MapLibre as an opt-in second nav engine; nav core kept engine-agnostic via `NavMapAdapter`.
- **PWA**: in Phase 4 alongside the mobile-first refresh — manifest + cache-first service worker for static assets only.
- **Geometry persistence**: routes recompute on load — not stored in DB.
- **Snap target**: nearest point on polyline geometry, ties to earliest index along route.
- **GPS playback**: built first in Phase 2 behind `?simgps=1` (`?simspeed=N` for ground-speed multiplier). Source: walks the route geometry; auto-slows near maneuvers.

## Observations from testing this session

- **Navigate stayed disabled until route was calculated** — fixed by setting `RouteManager.currentRoute` inside `displayRoute()` itself (not after it returned). The button-state check now sees the new route immediately.
- **Passthrough waypoints were being announced** — root cause: ORS emits type-11 (depart) and type-10 (goal/arrive) marker steps at every waypoint. Fixed in 3a by filtering both types out of the maneuver list. Final-destination arrival continues to work via the polyline-end check, not the goal step.

## Things known to be missing or rough

- No connector route at Navigate time yet — if the user starts far from the route polyline, the snap will pick a point with the user instantly "far ahead" of where they actually are (Phase 3b fixes this).
- No off-route handling — driving away from the route just means voice prompts go silent; nothing recovers (Phase 3c fixes this).
- No `duration > 0` arrival announcements or pause yet (Phase 3d).
- No Skip button yet (Phase 3e).
- Maneuver banner is plain text; no maneuver-type icons. Acceptable for v1 — polish later.
- Heading-up rotation deferred to Phase 5 (MapLibre swap).
- No mobile-first refresh of the planning view yet (Phase 4) — usable on phones but not optimised.

## References

- [NAVIGATION_PLAN.md](NAVIGATION_PLAN.md) — the spec; all design decisions and rationales
- [CLAUDE.md](CLAUDE.md) — project agent notes (env setup, build commands, layout)
- [CODE_REVIEW.md](CODE_REVIEW.md) — durable log of prior code-review issues; useful style/convention reference
- ORS instruction types: <https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/instruction-types> — the integers in `step.type` (10 = goal, 11 = depart, etc.)
- Web Speech API: <https://developer.mozilla.org/en-US/docs/Web/API/Web_Speech_API>
- Screen Wake Lock API: <https://developer.mozilla.org/en-US/docs/Web/API/Screen_Wake_Lock_API>
