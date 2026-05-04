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

> Note: saved routes are currently stored under a shared guest user and are reachable by anyone with the link (or who enumerates route IDs). Don't save anything you want to keep private.

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

# 4. run
./gradlew bootRun
```

Then open http://localhost:8090.

Developer documentation — architecture, module layout, API endpoints, WMS layer handling — lives in [HOWTO_LOCAL_MAP.md](HOWTO_LOCAL_MAP.md) and inline in the source. A full code review with known issues and priorities is in [CODE_REVIEW.md](CODE_REVIEW.md).

---

## Credits

- Maps: [OpenStreetMap](https://www.openstreetmap.org/) tiles via [Leaflet](https://leafletjs.com/)
- Weather data and map overlays: [National Weather Service](https://www.weather.gov/) (weather.gov, digital.weather.gov)
- Routing: [OpenRouteService](https://openrouteservice.org/)
- Geocoding and location search: [GeoApify](https://www.geoapify.com/)
- EV charging station data: [NREL Alternative Fuels Data Center](https://developer.nrel.gov/docs/transportation/alt-fuel-stations-v1/)

## License

MIT.
