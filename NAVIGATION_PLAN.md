# Navigation Feature — Implementation Plan

Real-time, voice-guided turn-by-turn navigation for saved routes, designed primarily for in-vehicle use on a mobile device.

## 1. Goal & user story

> *"Plan a trip, save it, then later open the app on my phone, load the route, tap **Navigate**, and have it guide me — by voice — from wherever I am to the nearest point on the route, and then to my destination."*

Concretely, an end-to-end navigation session looks like:

1. User opens the app on a mobile browser, loads a previously saved route.
2. User taps a **Navigate** button.
3. App requests precise geolocation and (optionally) screen wake-lock + audio permission.
4. App computes a **connector route** from the user's current location to the nearest snap-to-road point on the saved route's polyline.
5. App enters **navigation mode** — full-screen map, follow-and-rotate camera, large maneuver banner, voice prompts.
6. As the user drives, the app issues voice instructions ("In 400 m, turn right onto Elm Street", then "Turn right now") and visual cues, advancing through the maneuver list as positions stream in from the GPS.
7. If the user goes off-route, the app re-plans (with cooldown) and continues.
8. On arrival at the final waypoint, navigation mode ends.

## 2. Current state (anchor points in the codebase)

A quick inventory of what exists today and what's missing.

### Already in place
- **Routing backend**: [RouteService.java](src/main/java/com/pjr22/tripweather/service/RouteService.java) calls OpenRouteService at `/v2/directions/driving-car/geojson`. Geometry, distance, duration, and per-segment summary are parsed; **the `steps` (turn-by-turn instructions) field on the segment is declared but never populated** ([RouteService.java:222-235](src/main/java/com/pjr22/tripweather/service/RouteService.java#L222-L235)).
- **Saved-route persistence**: [Route.java](src/main/java/com/pjr22/tripweather/model/Route.java), [Waypoint.java](src/main/java/com/pjr22/tripweather/model/Waypoint.java), and the endpoints in `RoutePersistenceController` (`POST /api/routes`, `GET /api/routes/{uuid}`, `GET /api/routes/search/{text}`). Stored data is *waypoints only* — geometry and instructions are recomputed every time a route is loaded.
- **Map rendering**: Leaflet 1.9.4 ([index.html:14-16, 201-203](src/main/resources/static/index.html#L14-L16)). [RouteManager.js](src/main/resources/static/js/managers/RouteManager.js) renders the route polyline and segment labels.
- **Geolocation, lightly used**: [MapManager.js](src/main/resources/static/js/managers/MapManager.js) uses `navigator.geolocation.watchPosition()` (ACCURACY_GOAL_M = 10 m) but only to center the map at startup and on the "recenter" button.
- **Mobile baseline**: `<meta name="viewport" content="width=device-width, initial-scale=1.0">` is set; one `@media (max-width: 768px)` block exists in [styles.css:953](src/main/resources/static/css/styles.css#L953).
- **Frontend architecture**: Vanilla JS, namespaced under `window.TripWeather.*`, with a Utils → Services → Managers → app.js layering ([index.html:205-228](src/main/resources/static/index.html#L205-L228)). No build step.

### Not yet in place
- Turn-by-turn instructions in the routing response (the field exists, the parsing doesn't).
- Active position tracking, snap-to-polyline, off-route detection.
- Web Speech API / TTS anywhere in the codebase.
- Wake-lock, full-screen mode, screen-orientation handling.
- Service worker / PWA manifest (no offline support, not installable to home screen).
- Mobile-first layout for the map view (header, waypoint table, and modal sizing assume desktop).

## 3. Architecture overview

Navigation is **almost entirely a client-side feature**. The backend's job is small: include turn instructions in the route response. Everything else — GPS streaming, snap-to-route math, prompt scheduling, voice synthesis, UI state — lives in the browser. No WebSockets, no server-side session state.

```
Backend (Java)                          Frontend (browser)
──────────────                          ──────────────────
RouteService                            NavigationManager  ◄── new
  └─ instructions: true   ──────►         ├─ GeolocationStream
       in OpenRouteService                ├─ RouteSnapper       (project lat/lng onto polyline)
       request body                       ├─ ManeuverScheduler  (decides what to say, when)
                                          ├─ VoiceGuide         (Web Speech API)
RouteData.RouteSegment                    ├─ NavUIController    (banner, camera, exit)
  └─ adds steps[]                         └─ WakeLock helper
       (distance, duration,
        type, instruction,            MapManager        (existing, gets follow-mode added)
        name, way_points)             RouteManager      (existing, navigation route style)
                                      RoutePersistenceService  (existing, used to load route)
```

The two backend changes are intentionally tiny so the heavy lifting can iterate purely in the frontend.

## 4. Data flow during a navigation session

1. `RoutePersistenceService.load(uuid)` → existing endpoint returns the stored waypoints.
2. `RouteManager.calculateRoute(waypoints)` → existing endpoint, now returning `segments[].steps[]`.
3. **NavigationManager** is handed the resulting `RouteData`. It flattens all `segments[].steps[]` into a single ordered list of maneuvers, each carrying:
   - `instruction` (text), `name` (street), `type` (enum)
   - `distance`, `duration` (this maneuver to the next)
   - `wayPoints: [startIdx, endIdx]` indexing into the route's geometry array
   - Pre-computed `[lat, lng]` of the maneuver point (from `geometry[startIdx]`)
4. NavigationManager subscribes to `geolocation.watchPosition` with `{ enableHighAccuracy: true, maximumAge: 1000 }`.
5. On each fix:
   - **Snap**: project the position onto the route polyline → get `(snappedPoint, geometryIndex, crossTrackDistanceMeters)`.
   - **Off-route check**: if `crossTrackDistance > OFF_ROUTE_THRESHOLD_M` for `OFF_ROUTE_SUSTAINED_MS`, trigger re-plan (see §6.3).
   - **Maneuver advance**: walk the maneuver list using `geometryIndex` so we always know "which maneuver is next".
   - **Distance to next maneuver**: cumulative along-route distance from snapped position to the next maneuver point.
   - **Prompt scheduler** (see §6.2) decides whether to fire a voice prompt at this distance bucket.
   - **UI update**: maneuver banner shows next instruction + distance; map recenters/rotates if follow-mode is on.
6. On arriving at the final waypoint (within `ARRIVAL_RADIUS_M`), play "You have arrived" and exit nav mode.

The connector route is just step 2 with a synthesized two-waypoint request `[currentLocation, nearestPointOnRoute]`. Once the user joins the original route, the connector is discarded and we're back on the saved one.

## 5. Backend changes

Small and contained.

### 5.1 Request instructions from OpenRouteService

In [RouteService.calculateRoute()](src/main/java/com/pjr22/tripweather/service/RouteService.java#L101) the request body currently sets `coordinates`, `radiuses`, `elevation`. Add:

```java
request.setInstructions(true);                 // default, but make it explicit
request.setInstructionsFormat("text");         // plain text, not HTML
request.setLanguage("en");                     // configurable later — see §10
```

Add the matching fields to `RouteRequest` (the inner DTO). ORS already returns instructions when this flag is true; we only need to parse them.

### 5.2 Parse `steps` into the segment

Today [RouteSegment.steps](src/main/java/com/pjr22/tripweather/model/RouteData.java) is `List<Object>` and never populated. Replace it with a typed `List<RouteStep>` and populate it inside the segment-parsing loop in [RouteService.parseRouteResponseWithArrivalTimesAndDurations()](src/main/java/com/pjr22/tripweather/service/RouteService.java#L223):

```java
public class RouteStep {
    private double distance;        // meters, this step to next
    private double duration;        // seconds
    private int    type;            // ORS maneuver type 0..13
    private String instruction;     // "Turn right onto Main St"
    private String name;            // street name (may be empty)
    private List<Integer> wayPoints; // [startIdx, endIdx] into route geometry
    // exit number/exit bearings can be added later if needed for roundabouts
}
```

ORS maneuver type integers ([reference table](https://giscience.github.io/openrouteservice/api-reference/endpoints/directions/instruction-types) — confirm against the docs at implementation time): 0=left, 1=right, 2=sharp left, 3=sharp right, 4=slight left, 5=slight right, 6=straight, 7=enter roundabout, 8=exit roundabout, 9=u-turn, 10=goal, 11=depart, 12=keep left, 13=keep right.

Frontend will map these to icons and to spoken phrasing.

### 5.3 Why we don't persist instructions to the database

Routes are stored as waypoints, not geometry. The route is recomputed on every load (which keeps it fresh against road-network changes). Instructions are produced as a side-effect of that recompute — no DB migration needed. This is the right shape.

## 6. Frontend changes

Most of the work lives here. New module: `js/managers/NavigationManager.js` (with helpers in `js/nav/`).

### 6.1 Snap-to-route math

For each GPS fix, we need to find:
- the closest point on the route polyline to the user, and
- where on the polyline that point sits (so we can walk forward from there).

Algorithm: iterate every line segment `[geometry[i], geometry[i+1]]`, compute the perpendicular (cross-track) distance from the user to that segment, take the minimum. With route lengths in the low thousands of points this is fast enough to run on every fix without optimisation.

Two implementation notes:

- **Use equirectangular projection locally**, not great-circle math, for the perpendicular projection. The user is by definition close to the segment (otherwise we declare them off-route), so flat-earth math is accurate to centimetres at this scale.
- **Keep the previous fix's matched index** as a hint and search a small forward window first (say ±50 segments) before falling back to the full polyline. This avoids "matching backwards" past the user when the route doubles back near itself.

Cross-track output: snapped lat/lng, polyline segment index, fraction along that segment, perpendicular distance in meters.

### 6.2 Prompt scheduler — when to speak

Distance buckets to the next maneuver, fired exactly once each as the user crosses the threshold (driving toward the maneuver):

| Bucket           | Trigger distance | Phrasing                                     |
|------------------|------------------|----------------------------------------------|
| Far              | 2.0 km / 1.0 mi  | "In 1 mile, turn right onto Elm Street."     |
| Mid              | 500 m / 0.25 mi  | "In a quarter mile, turn right."             |
| Near             | 150 m            | "In 500 feet, turn right."                   |
| Now              | 30 m             | "Turn right now."                            |
| After            | post-maneuver    | (silent — next maneuver takes over)          |

(Distances are easily configurable; constants in `NavigationConstants.js`.)

The "Far" prompt is suppressed when the previous maneuver's "After" point is within e.g. 400 m of the next maneuver — i.e., when maneuvers are bunched, we collapse them ("Turn right, then immediately turn left").

**Waypoint announcements** use the same buckets but different phrasing, and only fire for waypoints with `duration > 0` (see §6.7): "Arriving at Mom's House in half a mile" → "You have arrived at Mom's House." Waypoints with `duration == 0` are route-shape only and produce no announcements.

Speech queue rules:
- New prompt cancels the currently-speaking one if it would otherwise overlap (Web Speech: `speechSynthesis.cancel()` then `speak()`).
- Each maneuver remembers which buckets have already fired so we never repeat.
- On re-route, the queue is cleared and the new maneuver list takes over.

### 6.3 Off-route detection and re-planning

Off-route when **cross-track distance > 50 m sustained for > 10 s** (these need road-test tuning; numbers chosen to tolerate GPS jitter and momentary lane drift).

**Strategy for v1: guide-back-to-route.** We don't recompute the planned route — we keep it as the source of truth and produce a short connector from where the user is now back to the nearest *forward* point on the saved polyline.

On off-route:
1. Cancel the current voice prompt.
2. Say "Re-routing."
3. Find the nearest **forward** point on the saved route polyline — i.e. searching from the maneuver index the user was last tracking against, not from the start. (Searching the whole polyline can pick a closer earlier point and send the user backwards through stops they've already done.)
4. Call backend to compute a connector route from `currentLocation` to that forward point. The connector arrives with its own maneuver list.
5. Splice: active maneuver list = `connector.steps[] + savedRoute.steps[fromForwardIndex..]`.
6. Render the connector portion in the dashed-orange "join the route" style (same visual treatment as the initial connector in §6.5).
7. When the user reaches the join point (within `ON_ROUTE_THRESHOLD_M`), drop the connector and the saved route's polyline returns to normal blue.
8. Apply a **20 s cooldown** before another re-route can fire, so we don't thrash if GPS is unreliable.

If the re-route call fails (offline / API down), keep the original route active and notify visually but don't keep retrying.

Off-route detection is **suppressed while paused at a stop** (see §6.7). On Continue, off-route detection re-enables; if the user's current position is no longer on the route (parked elsewhere, moved the car), the standard guide-back flow takes over immediately.

**Future hybrid (v2):** add a fallback to a full "re-route through remaining waypoints" when guide-back would produce a long or implausible connector — e.g. if the nearest forward point is more than `MAX_CONNECTOR_M` away, or if the connector itself crosses back over the saved route in a weird shape. The infrastructure built here (connector splicing) is the right substrate for that fallback to plug into.

### 6.4 Voice synthesis (Web Speech API)

```js
const utter = new SpeechSynthesisUtterance(text);
utter.rate = 1.0;
utter.lang = 'en-US';
window.speechSynthesis.speak(utter);
```

Constraints worth flagging in code comments because they're easy to forget:

- **iOS Safari requires a user gesture** to unlock TTS the first time. Solution: have the user tap the **Navigate** button to start the session — that gesture unlocks audio for the duration. Tapping a "Test voice" button on first run is a good belt-and-braces approach.
- **TTS suspends when the tab is backgrounded** on most mobile browsers. We mitigate this with screen wake-lock (§6.6); fully backgrounded use is out of scope (see §10.5).
- **Voice selection** is async — `speechSynthesis.getVoices()` returns `[]` until the `voiceschanged` event fires. Cache the chosen voice once available.
- **Long queues misbehave** on some browsers (utterances dropped after ~15 s). We never queue more than the next single utterance.

### 6.5 Connector route to nearest point

When the user taps **Navigate**:

1. Get a single high-accuracy fix (`getCurrentPosition`, not `watchPosition` yet — we want to know we have a fix before showing nav UI).
2. Run snap-to-route math against the saved route's polyline → nearest point.
3. If user is **already within `ON_ROUTE_THRESHOLD_M` (e.g. 30 m)** of the polyline, skip the connector entirely and start navigating from the matched position.
4. Otherwise call `POST /api/route/calculate` with `[currentLocation, nearestPoint]` to get a proper driving connector (with its own instructions).
5. Render the connector in a **distinct style** (e.g., dashed orange) so the user can see they're in "join the route" mode.
6. When the user reaches the joining point (within `ON_ROUTE_THRESHOLD_M`), the connector is discarded, the saved route's instructions take over, and the polyline returns to the normal blue.

If `getCurrentPosition` denies or times out → show a clear error toast and abort. We don't fall back to anything; navigation without GPS is meaningless.

### 6.6 Mobile UX (the navigation view)

A dedicated layout — *not* the existing planning view — is shown while in nav mode:

- **Full-screen map** taking the entire viewport. Header, waypoint table, and other planning UI are hidden via a `body.nav-mode` CSS class.
- **Top banner**: large maneuver icon + distance to next maneuver + street name. Big enough to read at arm's length, high contrast.
- **Bottom strip**: ETA to final waypoint, remaining distance, and an obvious **Exit Navigation** button.
- **Camera**: follows the user, zoomed in (≈18). For v1, **north-up on Leaflet**; the position dot rotates to show direction of travel but the map itself doesn't. Heading-up via MapLibre is planned as a v2 alternative the user can opt into; Leaflet north-up will remain as the other option (and as the planning-view engine). See §10.6.
- **Engine-agnostic nav core**: keep `NavigationManager`, `RouteSnapper`, `ManeuverScheduler`, and `VoiceGuide` free of any direct Leaflet calls. Camera and overlay updates go through a thin `NavMapAdapter` interface (`recenter(latLng)`, `setZoom(z)`, `drawConnectorPolyline(coords)`, etc.) with a `LeafletNavMapAdapter` implementation for v1. The v2 MapLibre option becomes a second adapter rather than a rewrite.
- **Wake lock**: request `navigator.wakeLock.request('screen')` on entering nav mode; release on exit. Re-acquire on `visibilitychange` because the lock drops when the tab loses visibility.
- **Touch targets**: ≥ 44 × 44 px (Apple HIG), high contrast for sunlight readability.

### 6.7 Intermediate waypoints, stops, and skip

The trip's planned waypoints carry a `duration_min` field that drives navigation behaviour at each one:

**Duration > 0 ("real stop"):** the waypoint is *mandatory* — the only ways to be done with it are to **arrive** within `ARRIVAL_RADIUS_M`, or for the user to explicitly **Skip** it.

- Announce arrival on approach using the §6.2 buckets ("Arriving at Mom's House in half a mile" → "You have arrived at Mom's House").
- **Arrived** = user's location enters `ARRIVAL_RADIUS_M` of the waypoint at any point. Triggers **pause navigation**: voice prompts off, off-route detection off, position dot still updates on the map, maneuver banner shows "Stopped at [name] — tap Continue when ready."
  - Pause is open-ended. The user might be there for hours or days. Only **Continue** (or reloading the route and restarting) resumes navigation.
  - On Continue: resume from the next maneuver after this waypoint. If the user's current position is no longer on the route (parked elsewhere, moved the car), the standard guide-back flow (§6.3) takes over immediately.
- **Driving past the waypoint geometrically does *not* count as done.** If the user's snapped position advances past the waypoint's polyline index without having entered `ARRIVAL_RADIUS_M`, the waypoint remains the next required destination. The standard off-route detection (§6.3) will fire and produce guide-back routes targeted at the waypoint itself — so the navigator keeps trying to deliver the user there. The 20 s re-route cooldown applies, limiting API churn even if the user keeps driving away.
- **Skip button:**
  - Appears when distance to the waypoint first drops below `SKIP_AVAILABLE_DISTANCE_M` (~2 miles / 3.2 km).
  - Once appeared, **remains visible** until the user either taps it or arrives within `ARRIVAL_RADIUS_M` — including the entire "driving past + being routed back" period. This guarantees the user always has an escape from the re-route loop.
  - On Skip: drop the waypoint, then trigger guide-back to the saved route at the nearest forward point *past* the skipped waypoint, and resume the original instructions from there (i.e. the same machinery as §6.3 guide-back, with the search starting from the waypoint's polyline index rather than the user's last on-route index).

**Duration == 0 ("passthrough"):**
- Pure route shape. No arrival prompt, no pause, no skip banner. The user can drive past freely.

**Final waypoint:**
- Always ends the navigation session on entering `ARRIVAL_RADIUS_M`, regardless of duration — there's nothing to continue *to*. Speak "You have arrived at your destination," exit nav UI to the planning view, release the wake lock.

### 6.8 Mobile-first refresh of the planning view (necessary scaffolding)

Even outside navigation, the planning view needs to be usable on a phone for the workflow "load route → hit Navigate". Concrete deltas:

- The waypoint table (12 columns, [index.html:84-106](src/main/resources/static/index.html#L84-L106)) does not fit a phone screen. Below ~600 px, switch to a stacked card-per-waypoint layout (each card showing date/time, location, weather summary, actions).
- Header buttons (`🆕 New / 💾 Save / 📁 Load / 🔗 Share / 🚗 Calculate / 🔍 Search`) wrap awkwardly. Below ~600 px, collapse non-essential ones into an overflow menu, keep **Load Route** and **Navigate** prominent.
- Modal sizing assumes desktop. Below ~600 px, modals should be near-full-screen.
- Map height currently competes with the table for vertical space — on mobile, give the map a fixed minimum height (e.g. 50 vh) and let the waypoints scroll below.

This mobile pass is real work — a full plan would itemise per-component breakpoints — but the navigation feature can't ship usably without it.

## 7. New files & modules (proposed)

```
src/main/java/com/pjr22/tripweather/
  model/
    RouteData.java                    # RouteSegment.steps becomes List<RouteStep>
    RouteStep.java                    # NEW — typed step DTO
  service/
    RouteService.java                 # request instructions, parse steps

src/main/resources/static/
  index.html                          # add Navigate button, nav-mode UI scaffolding
  css/
    styles.css                        # mobile breakpoints
    nav.css                           # NEW — navigation-mode styles
  js/
    nav/                              # NEW directory
      RouteSnapper.js                 # snap-to-polyline math
      ManeuverScheduler.js            # bucket logic, dedup, scheduling
      VoiceGuide.js                   # Web Speech wrapper
      WakeLock.js                     # screen-lock helper
      NavigationConstants.js          # all distance / time thresholds
    managers/
      NavigationManager.js            # NEW — orchestrator
      MapManager.js                   # add follow-mode + zoom-to-nav helpers
      RouteManager.js                 # surface segments[].steps to NavigationManager
    services/
      RoutePersistenceService.js      # no change
```

PWA scaffolding (separate stretch goal — see §10.7):
```
src/main/resources/static/
  manifest.webmanifest
  sw.js
```

## 8. Phasing

A suggested cut so we can ship something usable early and iterate.

### Phase 1 — Backend + data shape
- Request `instructions: true`, parse `steps` into typed DTOs.
- Verify in a manual test that a planned route returns sensible turn-by-turn steps.
- No frontend changes yet; just confirm the data is there.

### Phase 2 — Navigation engine (desktop development, mobile-form-factor testing)
- Build the **`PositionSource` abstraction first** (`LivePositionSource` over `geolocation.watchPosition`, `PlaybackPositionSource` reading a GPX file or interpolating along the saved route at 1×/5×/25× speed, behind a `?simgps=1` debug flag). Everything downstream depends only on the abstraction so the engine is testable from day one without driving.
- Implement `RouteSnapper`, `ManeuverScheduler`, `VoiceGuide`, `WakeLock`, `NavigationManager`, and the `NavMapAdapter` interface with a `LeafletNavMapAdapter` implementation (see §6.6).
- Add a **Navigate** button.
- Render the maneuver banner and exit button. Voice prompts working.

### Phase 3 — Connector route + off-route handling
- Snap-to-nearest-point + connector route on Navigate.
- Off-route detection + re-planning with cooldown.

### Phase 4 — Mobile-first planning view + PWA shell
- Responsive waypoint table (stacked cards), responsive modals, responsive header buttons.
- Touch-target audit.
- `manifest.webmanifest` with icons (existing favicon set covers most sizes), theme colour, `display: standalone`.
- `sw.js` — minimal cache-first service worker for static assets (HTML / CSS / JS / icons). No offline routing; map tiles and the routing API still require network.
- "Add to Home Screen" verified on iOS Safari and Android Chrome.

### Phase 5 — v2 candidates (deferred)
- MapLibre as a second nav-map engine (heading-up rotation), with Leaflet remaining as the planning engine and as a nav option.
- Hybrid off-route fallback (full re-route through remaining waypoints when guide-back would be implausible — see §6.3).
- Settings dialog wiring up the centralised constants (units, voice, language).

## 9. Risks & web-platform realities

These are the things most likely to bite us. Worth being honest about them up front.

- **Background audio is unreliable.** Web Speech API stops when the tab is backgrounded or the screen turns off on most mobile browsers. The realistic use case is a **mounted phone with the screen on** (which the wake-lock supports). True background navigation isn't achievable in a pure web app — it would need a native or hybrid wrapper.
- **iOS Safari quirks.** TTS needs a gesture to unlock; geolocation can be throttled in low-power mode; wake lock works but is occasionally lost on visibility changes (we handle this).
- **GPS accuracy is uneven** in cities with tall buildings and under tree cover. `enableHighAccuracy: true` helps but doesn't eliminate jitter — hence the sustained-distance off-route check.
- **OpenRouteService rate limits.** The free tier is ~40 requests/min. Re-route storms could blow that. The 20 s cooldown plus the off-route confirmation window keep us comfortably under in normal driving, but pathological GPS conditions could still trip it. We should monitor.
- **No real backend telemetry.** If something goes wrong mid-drive there's no log. Worth adding a lightweight client-side error catcher that POSTs nav-session anomalies (off-route count, re-route failures, TTS errors) to the backend for diagnosis. Not blocking for v1.
- **Battery drain.** High-accuracy GPS + screen wake + map redraws + TTS will drain a phone battery fast. Users will need a car charger. Document this; consider a "Low power" mode later (less frequent updates, no map redraws between fixes).

## 10. Open questions

These are decisions that affect scope and behaviour. I've proposed defaults but want your call before building.

1. **Off-route re-planning aggressiveness.** ✅ *Decided: v1 uses guide-back-to-route (connector from current location to nearest forward point on the saved polyline, splice in front of remaining maneuvers). Thresholds: 50 m / 10 s / 20 s cooldown — to be road-tuned. Future v2: hybrid that falls back to full re-route through remaining waypoints when the connector would be implausibly long or shaped. See §6.3.*

2. **Re-route target — through all remaining waypoints, or straight to the final destination?** ✅ *Decided: with guide-back chosen for v1, this is moot — the original route (and all its waypoints) is preserved by construction. Waypoint semantics: `duration > 0` waypoints are real stops the user must arrive at (or actively Skip); `duration == 0` waypoints are silent passthroughs. See §6.7.*

3. **Stops at intermediate waypoints.** ✅ *Decided: `duration > 0` waypoints are mandatory destinations. Arrival within `ARRIVAL_RADIUS_M` pauses navigation with an open-ended **Continue** button (pause may last hours or days). Driving past geometrically does **not** count as done — off-route detection fires and produces guide-back routes targeted at the waypoint itself, with the 20 s cooldown limiting API churn. The user's only escape is the **Skip** button, which appears when distance first drops below ~2 miles and remains visible until tapped or arrival. On Skip, guide-back to the nearest forward point on the saved route past the skipped waypoint. `duration == 0` waypoints are silent passthroughs. The final waypoint always ends the session regardless of duration. See §6.7.*

4. **Voice / units / language.** ✅ *Decided: all hardcoded for v1 — miles + feet, system-default `en-US` voice, English instructions from ORS (`language: "en"`). Centralised in `NavigationConstants.js` so a future Settings dialog can wire them up without restructuring.*

5. **Backgrounded use.** ✅ *Decided: v1 is "phone in a mount, screen on, browser foregrounded." Web platform constraints (TTS pausing on tab background, GPS throttling) are accepted as expected behaviour. The Navigate gesture screen will explicitly tell the user to keep the screen on and the app open. Documented in the README. Native/hybrid wrappers (Capacitor, TWA) are out of scope.*

6. **Map rotation (heading-up).** ✅ *Decided: v1 ships north-up on Leaflet (position dot rotates, map doesn't). v2 will add MapLibre as an opt-in second nav-map engine with heading-up rotation; Leaflet north-up stays as the other nav option and as the planning-view engine. v1 architectural requirement: nav core stays map-engine-agnostic via a `NavMapAdapter` interface so the MapLibre option is a second adapter rather than a rewrite. See §6.6.*

7. **PWA / installable.** ✅ *Decided: included in Phase 4 alongside the mobile-first refresh. Minimal scope — manifest + cache-first service worker for static assets only. Map tiles and routing API remain online-required. Treated as mobile-experience polish, not as offline navigation.*

8. **Save the computed route geometry to the DB?** ✅ *Decided: keep recomputing on load. Always-current routing is the right behaviour for navigation; no DB schema change needed; the ORS-unavailable failure mode is rare and recoverable. Future offline navigation, if ever pursued, would cache geometry + instructions client-side (service worker / IndexedDB), not in the server DB.*

9. **"Snap to nearest point along the route" — definition.** ✅ *Decided: nearest point on the polyline geometry, with ties resolved to the earliest index along the route (so doubles-back hairpins prefer the outbound leg). The connector route handles drivability regardless. If road-testing reveals problems, may surface a user choice in v2.*

10. **Simulated GPS playback for testing.** ✅ *Decided: included in Phase 2, built before snap-to-route so the engine is testable against simulated traces from day one. Behind a debug flag (`?simgps=1`). Sources: GPX file drop, interpolated path along the saved route, with 1×/5×/25× speed control. Implementation: a `PositionSource` interface with `LivePositionSource` and `PlaybackPositionSource` implementations — the rest of `NavigationManager` doesn't know which is in use.*
