# Trip Weather - Implementation Plan

## Current Implementation Overview
The application currently implements the three core stages described in the original plan:

### Stage 1 – Map Display
- `HomeController` serves `index.html`.
- Leaflet map initialized with user geolocation (fallback to US centre).
- Re‑center control and loading overlay implemented.

### Stage 2 – Waypoint Management
- Click‑to‑add waypoints, drag‑and‑drop reordering, delete, edit date/time/location.
- Waypoint markers show order number and popup with location/weather details.
- Reverse‑geocoding and location search via `LocationService` (OpenRouteService).

### Stage 3 – Weather Forecast Integration
- `WeatherService` contacts `weather.gov` to obtain hourly forecasts.
- `WeatherController` exposes `/api/weather/forecast` endpoint.
- Front‑end fetches weather when date & time are set, displays condition, temperature, wind, precipitation, and icon.

## Next Steps (Enhancements)

1. **Intelligent Routing - COMPLETE**
   - Add a button to trigger routing. Something like "Calculate Route" or "Optimize Route".
   - Use OpenRouteService “directions” endpoint to compute optimal routes between consecutive waypoints.
   - Draw polylines on the map representing the route.
   - Store route geometry for later export or distance calculations.
   - If waypoints are re-ordered or deleted, remove the old route, but don't recalculate until the user clicks the "Calculate Route" button.
   - If the user clicks "Calculate Route" again, recalculate the route and update the map.

2. **Automatic Arrival Time Calculation**
   - After routing, compute travel time between waypoints.
   - Propagate arrival times forward from the first waypoint’s departure date/time.
   - Allow user to edit the first waypoint’s departure; subsequent waypoints update automatically.
   - Allow user to add a Duration to each waypoint, representing the amount of time to spend at that location. Use this to calculate the next waypoint’s departure time. Use minutes as the unit of measurement. Use 0 as the default value.
   - Make sure times at each waypoint reflect the timezone of that location.

3. **Persist Routes**
   - Add a simple JPA entity (`Route`) with waypoints and timestamps.
   - Provide “Save” and “Load” buttons to store routes in an embedded H2 database.

4. **Export / Reporting**
   - Generate a PDF/HTML summary of the trip with waypoints, dates, and weather forecasts.
   - Optionally include a printable map snapshot.

5. **UI/UX Improvements**
   - Dark mode toggle.
   - Mobile‑responsive layout.
   - Better error handling UI for API failures.

## Identified Issues / Suggested Fixes

- **OpenRouteService API key handling**: Currently read from `application.properties`; consider externalizing to an environment variable for security.
- **Weather time‑zone handling**: `WeatherService` parses target date/time as a local `LocalDateTime` and then applies the timezone of the first forecast period. This may cause mismatches for waypoints in different time zones. Suggest normalising all times to UTC before comparison.
- **No route visualization**: Waypoints are independent; users cannot see the path between them. Implement polyline drawing as part of the routing enhancement.
- **No persistence**: All data is lost on server restart. Adding a simple persistence layer will improve usability.

## Development Notes

- The project builds with Gradle (`./gradlew bootRun`).
- Ensure the OpenRouteService API key is present in `src/main/resources/application.properties` as `openrouteservice.api.key=YOUR_KEY`.
- The backend runs on port 8080 by default; the frontend is served from `src/main/resources/static`.
