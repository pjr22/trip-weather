# Navigation Feature — Handoff

Picking up the navigation feature from another machine. Pair this with [NAVIGATION_PLAN.md](NAVIGATION_PLAN.md) — the plan is the spec, this is the in-flight status.

## Status at handoff

**Phases 1, 2, and 3 (3a–3e) are complete.** The navigation feature ships with:

- Backend turn-by-turn instructions parsed from ORS into typed `RouteStep` DTOs.
- A nav-mode UI (full-screen map, maneuver banner, Exit / Continue / Skip buttons), wake-lock, voice prompts via Web Speech, simulated GPS playback (`?simgps=1`).
- A connector route on Navigate that joins from off-route, with smart destination selection — duration waypoints are routed to (not skipped), and the connector swallows the next saved-route maneuver to encode it in the user's actual approach direction.
- Off-route detection (50 m / 10 s sustained, 20 s cooldown) with guide-back via the same connector machinery.
- Per-waypoint pause/Continue at duration > 0 stops, Skip button visibility within 2 mi of upcoming stops, drive-past detection that re-routes back to the missed waypoint.

**Next concrete step:** Phase 4 — mobile-first refresh of the planning view + PWA shell (manifest + cache-first service worker). See §6.8 and §8 of NAVIGATION_PLAN.md.

## What works today (verification checklist)

After resuming, smoke-test these to confirm nothing regressed:

1. `./gradlew bootRun` starts on `localhost:8090`.
2. Plan a multi-waypoint route, click **🚗 Calculate Route** — polyline appears, the **🧭 Navigate** button enables.
3. Click Navigate — "Updating your location…" overlay → nav-mode banner → voice says "Starting navigation."
4. With `?simgps=1`, the dot walks the route and voice prompts fire at FAR/MID/NEAR/NOW for each turn. Passthrough waypoints (duration 0) are silent.
5. With `?simgps=1&beginAtStart=0`, sim starts from your real GPS (capped at 5 mi from waypoint 1) and the dashed-orange connector polyline draws the connector path.
6. Approach a duration waypoint: voice fires "In 1 mile, arriving at [name]" → "In a quarter mile..." → "Arriving at [name]." On arrival within 50 m, voice says "You have arrived at [name]" and a green **Continue** button appears. Tapping Continue resumes navigation.
7. Skip button appears within ~2 mi of an upcoming duration waypoint and stays visible until tapped or arrival. Tapping Skip says "Skipping. Re-routing." and routes past the waypoint.
8. Driving past a duration waypoint without arriving (geometric pass) triggers "Re-routing to [name]" and a fresh connector back to the waypoint.
9. Live GPS off-route ≥10 s triggers "Re-routing." and a dashed-orange connector polyline back to the saved route. 20 s cooldown between re-routes.
10. On reaching the final waypoint, voice says "You have arrived at your destination" and the nav UI exits.
11. **Exit Navigation** button works at any time (mid-route, paused, mid-reroute).
12. Try `?simgps=1&simspeed=20` — fast cruise between turns, slows to real-time within 1 mi of each turn, ½ real-time within 0.25 mi.

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
|    3b | Connector route on Navigate (snap → compute → splice → join)           | ✅ done |
|    3c | Off-route detection + guide-back; 20s cooldown                         | ✅ done |
|    3d | `duration > 0` pause/Continue + arrival announcements                  | ✅ done |
|    3e | Skip button + drive-past handling (re-route targets the waypoint)      | ✅ done |
|     4 | Mobile-first planning view + PWA shell                                 | ⏳ next |
|     5 | v2 candidates: MapLibre alt, hybrid off-route, Settings                | future |

## Phase 3 — recap of what each sub-phase shipped

Where to look in the code is more useful now than re-stating the spec; the spec lives in [NAVIGATION_PLAN.md](NAVIGATION_PLAN.md).

- **3a** — ORS type-10/11 marker filtering and the `waypointStops[]` parallel structure: [NavigationManager._flattenManeuvers / _buildWaypointStops](src/main/resources/static/js/managers/NavigationManager.js).
- **3b** — Connector route on Navigate: `acquireUserLocation` reuse for the GPS fix, `_findConnectorDestination` priorities (planned-stop > maneuver-lookahead > perpendicular foot), `_assembleMergedRoute` for the merged geometry, visual trim split from data trim. Initial-Navigate landing on a duration waypoint preserves the synthetic stop entry.
- **3c** — Off-route detection in `_onPosition` (50 m / 10 s sustained), forward-only `RouteSnapper.snap` for the join hint, `_triggerReroute` + `_applyReroute` for guide-back. 20 s cooldown gates retries; on fetch failure the original merged route is kept and a toast surfaces.
- **3d** — Approach scheduler ([ManeuverScheduler.createForWaypointStops](src/main/resources/static/js/nav/ManeuverScheduler.js)) fires FAR/MID/NEAR prompts; `_handleWaypointStops` checks arrival within `ARRIVAL_RADIUS_M` and calls `_pauseAtWaypoint`. Paused state gates voice + off-route + scheduler in `_onPosition`. Continue button resumes via `_continueFromWaypoint`. Paused banner hue is green for visual distinction from active nav.
- **3e** — Skip button visibility tracked in `_handleWaypointStops` (2 mi → arrival/tap). `_handleSkip` marks the waypoint, advances `lastSavedSegmentIdx` past it, calls `_triggerReroute` with no explicit dest so the standard heuristic finds a forward point past the skip. Drive-past detection (user's polyline distance > stop + `ARRIVAL_RADIUS_M`) calls `_triggerReroute` with an explicit dest = the missed waypoint's location. `arrivedWaypoints` and `skippedWaypoints` sets carry across re-routes within a session, queried by `_firstSkippedDurationWaypoint` so the connector-destination logic doesn't re-target them.

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

- Maneuver banner is plain text; no maneuver-type icons. Acceptable for v1 — polish later.
- Heading-up rotation deferred to Phase 5 (MapLibre swap).
- No mobile-first refresh of the planning view yet (Phase 4) — usable on phones but not optimised.
- Re-route while waypoint approach prompts have already fired: prompts won't re-fire after the new merged geometry is applied (the waypoint scheduler is rebuilt fresh). Acceptable in practice — the user has just heard "Re-routing." and the next driving maneuver fires shortly after; arrival at the waypoint still triggers normally.
- Drive-past `_triggerReroute` uses the user's snapped position as the connector start (the raw GPS lat/lng isn't threaded down to that handler). For the small distances involved this is fine; only matters if the perpendicular offset between snap and real GPS is substantial.
- Phase 3d/3e were not yet road-tested when this section was written; behaviour under real GPS jitter near `ARRIVAL_RADIUS_M` may want tuning.

## References

- [NAVIGATION_PLAN.md](NAVIGATION_PLAN.md) — the spec; all design decisions and rationales
- [CLAUDE.md](CLAUDE.md) — project agent notes (env setup, build commands, layout)
- [CODE_REVIEW.md](CODE_REVIEW.md) — durable log of prior code-review issues; useful style/convention reference
- ORS instruction types: <https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/instruction-types> — the integers in `step.type` (10 = goal, 11 = depart, etc.)
- Web Speech API: <https://developer.mozilla.org/en-US/docs/Web/API/Web_Speech_API>
- Screen Wake Lock API: <https://developer.mozilla.org/en-US/docs/Web/API/Screen_Wake_Lock_API>
