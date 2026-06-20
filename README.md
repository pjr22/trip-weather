# Trip Weather

Plan a road trip, see the weather along the way, and find EV charging stations on your route.

**Try it live:** https://tripweather.pjr22.com

![Trip Weather is a web app for planning driving trips that shows you the weather forecast at each stop, at the time you'll actually be there.](src/main/resources/static/favicon-192x192.png)

## What it does

Trip Weather is a free web app for planning a driving trip. You drop pins on a map for each stop, set when you plan to arrive and how long you're staying, and it figures out:

- the **driving route** between your stops
- the **weather forecast** at each stop for the time you'll be there
- the **arrival and departure times** at each stop, accounting for drive time and any time zones you cross
- **EV charging stations** along your route (United States, via NREL)
- **map weather overlays** (temperature, precipitation probability, wind) for the time you'll be at each stop
- **turn-by-turn voice navigation** when it's time to drive — see [Navigation](#navigation-turn-by-turn) for what works and what doesn't

You can save routes, share them with a link, and come back later to load them. The app also works on phones — installable to the home screen as a Progressive Web App.

---

## A note from the author

This project is largely an AI coding experiment. Roughly 95% of the code, and most of this README, was written by an AI. The rest is my own bug fixes and tweaks. Some of the code is rough, and the UI could use a rewrite with more modern tooling. I may get around to that; in the meantime, I find it a genuinely useful tool for planning road trips — which is what it was built for, out of frustration with the lack of anything simple that combined routing and weather. Use it as-is, no warranty, no guarantees.

— PJR22

---

## Using the app

### Add a stop
- **Click the map** to drop a waypoint at that spot, or
- **Use the search box** to find a place by name or address.

The app automatically fills in the location name and the correct time zone.

### Set when you'll be there
Each waypoint has a **date**, **time**, and **duration** (how long you're staying). Duration accepts flexible formats like `1h30m`, `2d4h`, `90m`, or `1.5h`. Use the arrow buttons to nudge values up or down. The app recalculates arrival times at later stops automatically.

### See the weather
Each waypoint shows the forecast for the hour you'll be there: condition icon, temperature, wind, and precipitation probability. Forecasts come from the National Weather Service (U.S. only).

### See the route
Once you have two or more waypoints, the app calculates the driving route between them, shows it on the map with distance and drive-time labels, and displays overall stats: total distance, total drive time, and elevation gain/loss.

### Map weather overlays
Click the **layers** button on the map to toggle weather overlays — temperature, precipitation probability, wind, and more. The overlay's time matches the arrival time at the currently selected waypoint, so you see the forecast for when you'll actually be there.

### Find EV charging stations
Click the **EV charging** button to search for charging stations along your route. You can add any station to your trip as a waypoint. (United States only — data comes from the U.S. Department of Energy's NREL API.)

### Reorder, edit, delete
- **Drag** waypoints in the list to reorder them.
- **Click** a waypoint's name to rename it.
- **Delete** removes a stop and recalculates the route.

### Save and share
- **Save** names a route and stores it.
- **Share** copies a link you can send to anyone; opening that link loads the same route in their browser.
- **Load** opens a saved route by name.

Routes you save while signed in are private to your account — only you see them in **Load**, only you can delete them, and they live forever (no auto-expiry). Routes saved as a guest land in a shared bucket visible to every other guest, and they're swept 30 days after creation. **Share links** continue to work for anyone, regardless of who owns the route or whether the viewer is signed in — that's a UUID-based link, unchanged.

If you'd rather your routes weren't shared, [sign in or sign up](#account-optional) — it's free and the only data we ask for is an email address.

### Export

Once a route is saved, click **📤 Export** (under the ☰ menu on phones) to send the trip to other apps or download it as a file:

- **Google Maps** — opens driving directions in a new tab, or in the Google Maps app on iOS/Android.
- **GPX** — for GPS devices and fitness apps (Garmin, Komoot, Strava, RideWithGPS). Includes waypoints, the planned route, and the high-resolution track with elevation.
- **KML** / **KMZ** — for Google Earth and Google My Maps. KMZ is the same file, compressed.
- **GeoJSON** — for generic GIS and web tools.
- **CSV** — waypoints as a spreadsheet, with per-waypoint weather columns.

Limits worth knowing:

- The route must be **saved first** and have **at least two waypoints**.
- **Google Maps caps at 10 stops.** Longer routes are truncated to the first 10 with a warning toast — this is a Google Maps URL limit, not a Trip Weather one.
- **Weather is U.S. only.** CSV and GeoJSON include per-waypoint forecasts from the National Weather Service; waypoints outside U.S. coverage have empty weather cells. GPX, KML, and KMZ deliberately omit weather — those formats target GPS devices that ignore unknown data.

### Account (optional)

You can use Trip Weather without an account — just plan, calculate, and drive. An account only matters if you want your saved routes to be private to you.

Click the **profile icon** in the top right to access account actions:

- **Sign up** with email + password (12 characters minimum). You'll get a verification link in email; click it to finish setting up the account. The link is short-lived (5 minutes by default), so try it as soon as the email arrives. If it expires before you click, the modal offers a "resend" option.
- **Log in** with the same email + password. Tick **Stay logged in for 30 days** to skip the login screen on later visits — the cookie survives browser restart and is invalidated automatically if you change your password from another device.
- **Forgot password** — request a reset link on the login modal. The email lands the same way as the verification flow; click it to choose a new password. Your existing password keeps working until you successfully complete the reset, so a forgot-password request alone doesn't lock you out if you remember the old password.
- **Change password** (from the profile menu when logged in) — requires your current password, and invalidates "stay logged in" on every browser including the current one. You stay logged in for the rest of the current session.
- **Delete account** (from the profile menu when logged in) — requires your current password and an explicit acknowledgement that your saved routes will be deleted. Cascades to every route, waypoint, and login token tied to the account.

Email goes through [Mailtrap](https://mailtrap.io/). The only thing the app stores about you is your email address (lowercased, used as your login identifier), a BCrypt hash of your password, and the routes you save.

### Plan with AI (optional)

If the assistant is enabled on your server and you're signed in, you can describe a trip in plain language and let an AI suggest the stops for you.

First, add a provider once: **profile menu → AI Providers → Add provider**. Pick OpenAI, Anthropic, a self-hosted Ollama endpoint, or any OpenAI-compatible service, paste an API key (stored encrypted, never shown again), and choose a model from the discovered list.

Then click the **✨ AI Assist** button in the toolbar, pick the provider, and type something like *"a 3-day drive from Denver to Moab with scenic stops"*. The assistant returns an ordered list of stops, which the app geocodes into waypoints:

- If every stop is found, the route is built and loaded onto the map straight away.
- If a stop can't be located (or the list needs trimming), a **review** dialog opens listing every stop in order. Each row shows ✓ (found) or ✗ (not found) and is fully editable — fix the text and **Re-search**, **Delete** a stop you don't want, then **Use this route** once at least two stops resolve.

The result is an unsaved working route, exactly as if you'd added the stops yourself — calculate, tweak, and **Save** it like any other. Your prompt and your stored API key are sent to whichever provider you configured; nothing AI-related happens unless you opt in by adding a provider.

Once a route loads, the toolbar button turns green and becomes **AI Results** — click it any time to review what the assistant did: the description you typed, the model used, token usage (handy since you pay per request), how long it took, and the list of stops it suggested. Starting a **New Route** (or loading another) switches it back to **AI Assist**. Reasoning models can take a minute or more to answer, so the button shows a spinner while it works.

### Drive it
Once a route is calculated, **🧭 Navigate** starts a voice-guided drive. See the [Navigation section](#navigation-turn-by-turn) below — there are real web-platform limitations worth knowing before you rely on it.

---

## Navigation (turn-by-turn)

Plan a route on your computer, save it, then later open the app on your phone, load the route, mount the phone in the car, and tap **🧭 Navigate**. The app will:

- guide you from your current location to the start of the saved route via a short connector,
- speak turn-by-turn instructions ("In 1 mile, turn right onto Elm Street" → "In a quarter mile, turn right" → "Turn right now"),
- announce arrival at intermediate stops with a non-zero duration ("Arriving at Lunch Stop in half a mile" → "You have arrived at Lunch Stop"), and **pause** until you tap **Continue**,
- detect when you've gone off-route (sustained drift over ~10 s) and re-route you back, with a 20-second cooldown so it doesn't thrash,
- show a **Skip** button when you're approaching a planned stop, in case you decide en route to bypass it,
- end the session and return to the planning view when you reach the final waypoint.

Stops with **duration > 0** are treated as mandatory destinations — the only ways to be done with one are to arrive at it, or to tap **Skip**. Stops with **duration = 0** are silent passthroughs that just shape the route.

### Honest limitations

This is a web app, not a native one. That has consequences worth knowing before you trust it on a long drive:

- **Foreground only.** Voice guidance and high-accuracy GPS pause when the browser tab is backgrounded or the screen turns off. The realistic use case is a **phone mounted in the car, screen on, browser foregrounded** — which is exactly what it's designed for. A screen wake-lock keeps the screen alive while the app is open and visible.
- **No background navigation.** If you switch to another app mid-drive (to answer a call, change music, etc.), navigation pauses; switching back resumes from the current GPS fix. Don't rely on it to keep talking while you're in another app.
- **Network required throughout.** Map tiles, re-routing, and weather all require a connection. There is no offline navigation mode — if you lose signal, the route polyline and last-known position remain on screen but no new directions or re-routes will arrive until you're back online.
- **Map stays north-up.** The map doesn't rotate to your direction of travel; only the position dot rotates. Heading-up rotation is on the future-features list (it would require swapping the map engine).
- **TTS quality varies.** Voice prompts use the browser's built-in speech synthesis (Web Speech API). Voices and pronunciation differ across iOS, Android, and desktop browsers. On iOS Safari the very first prompt requires a user gesture to unlock audio — that gesture is the **Navigate** button tap, so always start a session by tapping Navigate yourself rather than relaunching from a backgrounded state.
- **Battery drain is real.** High-accuracy GPS, screen wake-lock, frequent map redraws, and TTS together will eat phone battery quickly. Plug into a car charger.
- **GPS jitter can trigger brief re-routes.** In city canyons, deep tunnels, or under heavy tree cover, position fixes get noisy. The 20 s re-route cooldown limits API churn, but you may briefly hear "Re-routing." prompts before the signal recovers.
- **U.S. coverage for weather, worldwide for routing.** The forecasts at each stop come from the U.S. National Weather Service and only cover the United States and territories. The driving directions themselves work wherever OpenRouteService does — i.e., almost everywhere.

If any of these limitations are deal-breakers for your use case, you want a native navigation app, not this one.

### Install to the home screen

Trip Weather installs as a Progressive Web App, which is much nicer than running it in a browser tab while you drive. On **iOS Safari**, tap the share button (square with an up arrow) and choose **Add to Home Screen**. On **Android Chrome**, tap the menu and choose **Install app** (or accept the install prompt if it appears). Once installed, it launches full-screen without browser chrome — the right experience for a phone in a car mount.

---

## Coverage and limits

- **Weather forecasts** and **weather map overlays** are from the U.S. National Weather Service and cover the continental United States, Alaska, Hawaii, and U.S. territories. Forecasts for locations outside that coverage will not load.
- **EV charging stations** are from NREL and cover the United States only.
- **Routing** (OpenRouteService) and **geocoding/search** (GeoApify) work worldwide.
- **Units** are imperial (miles, °F, feet).

---

## Troubleshooting

- **"Location not found" or no weather:** you may be outside the National Weather Service coverage area. Try a U.S. location.
- **Route isn't calculating:** make sure you have at least two waypoints and that each has valid coordinates. Refresh the page if the map didn't finish loading.
- **Map is blank:** check your network — the app loads map tiles from OpenStreetMap and the weather overlays from digital.weather.gov.
- **Saved route won't load:** saved routes use numeric IDs; verify the share link hasn't been truncated.
- **Times look wrong:** each waypoint stores its own time zone (detected when you add it). If you move a waypoint to a different zone, delete and re-add it so the zone is re-detected.

For bugs or suggestions, please open an issue on the project's GitHub page.

---

## Running it yourself

If you'd rather run a copy locally, you'll need Java 21 and PostgreSQL with PostGIS, plus free API keys from:

- [OpenRouteService](https://openrouteservice.org/) — routing
- [GeoApify](https://www.geoapify.com/) — geocoding and search
- [NREL Developer Network](https://developer.nrel.gov/) — EV charging stations

Quick start:

```bash
# 1. database (PostGIS) — by default the app connects to the standard `postgres` database;
#    just enable the PostGIS extension on it. To use a separate database, see step 3.
psql postgres -c "CREATE EXTENSION IF NOT EXISTS postgis;"

# 2. API keys and database password
export OPENROUTESERVICE_API_KEY=...
export GEOAPIFY_API_KEY=...
export NREL_API_KEY=...
export TRIP_DB_PASSWORD=...   # required; app refuses to start without it

# 3. (optional) point at a different database
# export TRIP_DB_URL='jdbc:postgresql://localhost:5432/yourdb'
# export TRIP_DB_USERNAME='youruser'

# 4. user-accounts setup — pick one path:
#
#    a) Skip the email + remember-me wiring during initial setup:
export TRIP_EMAIL_ENABLED=false
export TRIP_REMEMBER_ME_ENABLED=false
export TRIP_APP_BASE_URL=http://localhost:8090
export TRIP_COOKIE_SECURE=false
#
#    b) Or wire it up properly (Mailtrap free tier is fine for dev):
# export TRIP_EMAIL_URL='https://sandbox.api.mailtrap.io/api/send/<inbox-id>'
# export TRIP_EMAIL_APIKEY=<mailtrap-api-token>
# export TRIP_REMEMBER_ME_KEY="$(openssl rand -base64 48)"

# 5. operator console credentials (required unless you disable the console):
export TRIP_ADMIN_USERNAME=admin
export TRIP_ADMIN_PASSWORD="$(openssl rand -base64 24)"
#    or, to skip the admin console entirely while iterating:
# export TRIP_ADMIN_ENABLED=false

# 6. AI trip-planning assistant (on by default) — pick one path:
#
#    a) Enable it: set the key that encrypts users' saved provider API keys.
export TRIP_AI_ENC_KEY="$(openssl rand -base64 32)"
#    (optional) offer a local Ollama endpoint as a provider choice:
# export TRIP_AI_OLLAMA_URL=http://localhost:11434
#
#    b) Or skip it entirely (no enc-key needed, AI affordances hidden):
# export TRIP_AI_ASSIST_ENABLED=false

# 7. run
./gradlew bootRun
```

Then open http://localhost:8090.

When `TRIP_EMAIL_ENABLED=false`, the app logs would-be email bodies (verification and password-reset links included) at INFO level instead of sending them — useful for the verify / reset flow without provisioning Mailtrap. Set `TRIP_AUTH_EMAIL_TOKEN_LIFETIME_MINUTES` to a larger value if 5 minutes feels too tight while you're testing.

### Operator console

The app ships with a small operator console at **`/admin/`**, separate from the public SPA. Sign in with `TRIP_ADMIN_USERNAME` / `TRIP_ADMIN_PASSWORD` (set above) and you get four tabs:

- **Routes** — search, filter, sort, restore soft-deleted, hard-delete past the grace window, trigger cleanup on demand.
- **Data** — loader-run history, manual triggers for the cleanup / NREL / pbf jobs, and the operator surface for adding / scheduling / retrying OpenStreetMap pbf extracts that feed the self-hosted ORS engine.
- **Metrics** — live HTTP latency (p50/p95/p99), routing-dispatch counts (local vs. public vs. fallback, with per-reason breakdown), JVM heap, top URIs by request count, and cache hit ratios. Auto-refresh every 60 s, pause/resume, manual refresh.
- **Users** — paginated list of registered accounts; enable / disable / force-verify (clears stuck signup + reset tokens) / hard-delete (cascades the user's routes).

Authentication is a single shared credential held in env vars (BCrypt-hashed in memory at startup, never logged). The console lives on its own Spring Security filter chain and a namespaced session attribute, so it cannot accidentally hand a `ROLE_ADMIN` principal to user-chain endpoints.

The console reads everything the same `/actuator/prometheus` endpoint would expose, so you no longer need to leave actuator publicly reachable. See the full design and phasing in [ADMIN_CONSOLE.md](ADMIN_CONSOLE.md).

### AI trip-planning assistant

The optional AI assistant (the **✨ AI Assist** button, [described above](#plan-with-ai-optional)) is **on by default** but needs one secret: `TRIP_AI_ENC_KEY`, a Base64 32-byte key that encrypts each user's saved provider API keys at rest. Generate it once with `openssl rand -base64 32`; the app fails fast at startup if the feature is enabled and the key is missing. Rotating the key invalidates every stored API key. To run without the feature, set `TRIP_AI_ASSIST_ENABLED=false` and no key is required — the front-end hides the button and the AI Providers menu entry.

Users bring their own provider credentials (OpenAI, Anthropic, or any OpenAI-compatible endpoint); the key never leaves the server except on the outbound call to that provider, and is never returned by the API. Set `TRIP_AI_OLLAMA_URL` to offer a self-hosted Ollama endpoint as a keyless provider choice — that operator-set URL is trusted and bypasses the SSRF guard that vets user-supplied Custom base URLs (which must resolve to a public IP). See [AI_ASSIST_PLAN.md](AI_ASSIST_PLAN.md) for the design, the full env-var list, and the security model.

Reasoning models (GPT-5 / o-series and the like) routinely generate for a minute or more, so the outbound call defaults to a generous response timeout — `TRIP_AI_REQUEST_TIMEOUT_MS` (240s); raise it for especially slow models. **Mind any reverse proxy in front of the app:** a shorter proxy timeout returns a 504 while the backend is still working (and the model is still billing tokens). The bundled nginx sidecar already gives `/api/ai/` a long timeout; behind an additional terminator (e.g. haproxy) raise that layer's server/client timeout for the AI path to match.

### Going easier on the external APIs

Out of the box the app caches aggressively in Postgres — weather forecasts, NWS gridpoint lookups, reverse-geocodes, and OpenRouteService responses — so repeat clicks on the same waypoints, share-link reloads, and returning users mostly hit cache instead of upstream. NREL EV-station data is pulled into a local mirror once a week and served from there.

Two optional sidecars push it further, both documented in [LOCAL_CACHING_HOSTING.md](LOCAL_CACHING_HOSTING.md):

- An **nginx caching reverse proxy** in front of OpenStreetMap tiles and weather.gov icons. Browser hits the proxy; the proxy serves repeats from disk. Useful if a single deployment fans out to many users.
- A **self-hosted OpenRouteService engine** for the regions you care about, with automatic fallback to public ORS for routes that leave coverage. Driven by Geofabrik OSM extracts. Useful if your users do a lot of in-region routing and you have the RAM (~8 GB for the Western US extract; ~2 GB for a single state).

Both are off by default. The defaults go straight to the public APIs and need none of this to work.

Developer documentation — architecture, module layout, API endpoints, WMS layer handling — lives in [HOWTO_LOCAL_MAP.md](HOWTO_LOCAL_MAP.md) and inline in the source. The user-accounts feature has its own design document in [USER_ACCOUNTS_PLAN.md](USER_ACCOUNTS_PLAN.md), the caching / self-hosting plan is in [LOCAL_CACHING_HOSTING.md](LOCAL_CACHING_HOSTING.md), the operator console is documented in [ADMIN_CONSOLE.md](ADMIN_CONSOLE.md), and the AI trip-planning assistant is in [AI_ASSIST_PLAN.md](AI_ASSIST_PLAN.md). A full code review with known issues and priorities is in [CODE_REVIEW.md](CODE_REVIEW.md).

---

## Credits

- Maps: [OpenStreetMap](https://www.openstreetmap.org/) tiles via [Leaflet](https://leafletjs.com/)
- Weather data and map overlays: [National Weather Service](https://www.weather.gov/) (weather.gov, digital.weather.gov)
- Routing: [OpenRouteService](https://openrouteservice.org/)
- Geocoding and location search: [GeoApify](https://www.geoapify.com/)
- EV charging station data: [NREL Alternative Fuels Data Center](https://developer.nrel.gov/docs/transportation/alt-fuel-stations-v1/)

## License

MIT.
