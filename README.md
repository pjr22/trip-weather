# Trip Weather

Trip Weather is a Spring Boot web application that helps users plan road trips by allowing them to:

- Plot a route on an interactive map (OpenStreetMap via Leaflet.js).
- Add waypoints by clicking on the map.
- Assign a date and time to each waypoint.
- View real‑time weather forecasts for each waypoint using the National Weather Service API (weather.gov).

## Current Features

- **Map display** with automatic centering on the user's location (fallback to continental US center).
- **Waypoint management**:
  - Add, delete, reorder (drag‑and‑drop) waypoints.
  - Edit waypoint metadata (date, time, custom location name).
- **Location services**:
  - Reverse‑geocode waypoint coordinates via OpenRouteService.
  - Search for locations by name/address using OpenRouteService.
- **Weather integration**:
  - Fetch hourly forecasts for a waypoint’s latitude/longitude, date, and time.
  - Display condition, temperature, wind, precipitation probability, and icon.
- **User interface**:
  - Responsive table showing waypoint data and weather.
  - Loading indicators for location and weather requests.
  - Controls for recentering on the user’s location and for searching locations.

## Technology Stack

- **Backend**: Spring Boot 3.5.7, Java 21
- **Frontend**: HTML5, CSS, JavaScript, Leaflet.js
- **APIs**:
  - Weather: `https://api.weather.gov`
  - Geocoding / Search: OpenRouteService (`openrouteservice.org`) – API key stored in `openRouteService_api_key.txt`
- **Build**: Gradle

## Getting Started

1. Ensure Java 21 and Gradle are installed.
2. Place your OpenRouteService API key in `src/main/resources/application.properties` as `openrouteservice.api.key=YOUR_KEY`.
3. Run the application:

   ```bash
   ./gradlew bootRun
   ```

4. Open `http://localhost:8080` in a browser.

## Future Enhancements (see updated IMPLEMENTATION_PLAN.md)

- Intelligent routing between waypoints using OpenRouteService.
- Automatic arrival time calculation based on departure time and routing distances.
- Persisting routes to a database, exporting to PDF, dark mode, etc.
