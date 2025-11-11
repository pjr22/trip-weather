# Trip Weather

Trip Weather is a Spring Boot web application that helps users plan road trips by allowing them to:

- Plot a route on an interactive map (OpenStreetMap via Leaflet.js).
- Add waypoints by clicking on map.
- Assign a date, time, and duration to each waypoint.
- View real-time weather forecasts for each waypoint using National Weather Service API (weather.gov).
- Calculate optimal routes between waypoints using GeoApify routing service.

## Current Features

- **Map display** with automatic centering on user's location (fallback to continental US center).
- **Waypoint management**:
  - Add, delete, reorder (drag-and-drop) waypoints.
  - Edit waypoint metadata (date, time, duration, custom location name).
- **Location services**:
  - Reverse-geocode waypoint coordinates via GeoApify with timezone information.
  - Search for locations by name/address using GeoApify.
  - Timezone data automatically included in location responses for accurate time calculations.
- **Weather integration**:
  - Fetch hourly forecasts for a waypoint's latitude/longitude, date, and time.
  - Display condition, temperature, wind, precipitation probability, and icon.
- **Route calculation**:
  - Calculate optimal routes between waypoints using GeoApify routing API.
  - Visualize routes with colored polylines on the map.
  - Automatic arrival time calculation based on travel time and waypoint durations.
- **Duration management**:
  - Add duration to waypoints representing time spent at each location.
  - Support for flexible duration input (e.g., "3d2h10m", "48h22m", "1.5h").
  - Increment/decrement duration with arrow buttons.
- **User interface**:
  - Responsive table showing waypoint data and weather.
  - Loading indicators for location and weather requests.
  - Controls for recentering on user's location and for searching locations.
  - Modern, modular JavaScript architecture.

## Technology Stack

- **Backend**: Spring Boot 3.5.7, Java 17
- **Frontend**: HTML5, CSS, JavaScript (ES6+), Leaflet.js
- **Architecture**: Modular JavaScript with separation of concerns
  - Utilities (DurationUtils, TimezoneUtils, IconLoader, Helpers)
  - Services (LocationService, WeatherService)
  - Managers (MapManager, WaypointManager, WaypointRenderer, SearchManager, UIManager, RouteManager)
  - Application coordinator (app.js)
- **APIs**:
  - Weather: `https://api.weather.gov`
  - Geocoding / Search / Routing: GeoApify (`geoapify.com`)
- **Build**: Gradle

## Getting Started

1. Ensure Java 17 is installed. Gradle wrapper is included in the project.
2. Set your GeoApify API key as an environment variable, `GEOAPIFY_API_KEY`, or in `src/main/resources/application.properties` as `geoapify.api.key=YOUR_KEY`.
3. Run the application:

   ```bash
   ./gradlew bootRun
   ```

4. Open `http://localhost:8090` in a browser.

## Toast Notifications

Toast notifications appear in the upper-center of the screen by default. You can change their placement at runtime using the helper utilities:

```js
// Example: src/main/resources/static/js/app.js
window.TripWeather.Utils.Helpers.setToastPosition('lower-right');
// or configure multiple defaults at once
window.TripWeather.Utils.Helpers.configureToasts({ position: 'lower-right' });
```

Valid positions are `upper-left`, `upper-center`, `upper-right`, `lower-left`, `lower-center`, and `lower-right`.

## Project Structure

```
src/main/resources/static/js/
├── app.js                    # Main application coordinator
├── utils/                     # Utility functions
│   ├── DurationUtils.js        # Duration parsing, formatting, validation
│   ├── TimezoneUtils.js        # Timezone handling and DST support
│   ├── IconLoader.js          # SVG icon loading
│   └── Helpers.js             # General utilities and HTTP helpers
├── services/                  # API service layer
│   ├── LocationService.js       # Geocoding and location services
│   └── WeatherService.js       # Weather API integration
└── managers/                  # UI and feature managers
    ├── MapManager.js           # Map initialization and controls
    ├── WaypointManager.js      # Waypoint data management
    ├── WaypointRenderer.js     # Table rendering and marker management
    ├── SearchManager.js         # Location search functionality
    ├── UIManager.js            # UI overlays and interactions
    └── RouteManager.js         # Route calculation and display
```

## Future Enhancements

1. **Data persistence**:
   - Save routes to embedded H2 database.
   - Export routes to PDF/HTML format.
2. **UI/UX improvements**:
   - Dark mode toggle.
   - Enhanced mobile responsiveness.
   - Better error handling UI.
3. **Advanced features**:
   - Route optimization options (fastest, shortest, scenic).
   - Trip sharing and collaboration.
   - Historical weather data for planning.
