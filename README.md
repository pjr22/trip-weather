# Trip Weather

## Repository owner's note:
This project is largely an AI coding experiment. Approximately 95% (or more) of code, including this README document, is AI generated, using a variety of AI tools and models. The rest was written by me (PJR22), as mostly bug fixes and minor improvements. A lot of code is bad, and the UI could probably benefit from a complete rewrite using more modern technologies. Some improvements will continue to be made, and I may take on larger, more ambitious refactoring efforts at some point, as I find this to be a useful tool for planning road trips. It was, in fact, created out of frustration with the lack of a simple, user-friendly tool for planning road trips that includes weather information along the route. The code is offered as-is, without any warranty or guarantee of functionality. Use it as you see fit.

Trip Weather is a Spring Boot web application that helps users plan road trips by allowing them to:

- Plot a route on an interactive map (OpenStreetMap via Leaflet.js)
- Add waypoints by clicking on map or searching for locations
- Assign a date, time, and duration to each waypoint
- View real-time weather forecasts for each waypoint using National Weather Service API (weather.gov)
- Calculate optimal routes between waypoints using OpenRouteService API
- Save, load, and share routes with PostgreSQL database persistence

## Current Features

- **Map display** with automatic centering on user's location (fallback to continental US center)
- **Waypoint management**:
  - Add, delete, reorder (drag-and-drop) waypoints
  - Edit waypoint metadata (date, time, duration, custom location name)
- **Location services**:
  - Reverse-geocode waypoint coordinates via GeoApify with timezone information
  - Search for locations by name/address using GeoApify
  - Timezone data automatically included in location responses for accurate time calculations
- **Weather integration**:
  - Fetch hourly forecasts for a waypoint's latitude/longitude, date, and time
  - Display condition, temperature, wind, precipitation probability, and icon
- **Route calculation**:
  - Calculate optimal routes between waypoints using OpenRouteService routing API
  - Visualize routes with colored polylines on map
  - Display distance labels on route segments showing distance between waypoints
  - Automatic arrival time calculation based on travel time and waypoint durations
- **Duration management**:
  - Add duration to waypoints representing time spent at each location
  - Support for flexible duration input (e.g., "3d2h10m", "48h22m", "1.5h")
  - Increment/decrement duration with arrow buttons
- **Route persistence**:
  - Save routes to PostgreSQL database with user management
  - Load previously saved routes
  - Share routes via shareable links
  - Route naming and organization
- **User interface**:
  - Responsive table showing waypoint data and weather
  - Loading indicators for location and weather requests
  - Controls for recentering on user's location and for searching locations
  - Modern, modular JavaScript architecture

## Technology Stack

- **Backend**: Spring Boot 3.5.7, Java 17
- **Database**: PostgreSQL with PostGIS for spatial data
- **Frontend**: HTML5, CSS, JavaScript (ES6+), Leaflet.js
- **Architecture**: Modular JavaScript with separation of concerns
  - Utilities (DurationUtils, TimezoneUtils, IconLoader, Helpers)
  - Services (LocationService, WeatherService, RoutePersistenceService)
  - Managers (MapManager, WaypointManager, WaypointRenderer, SearchManager, UIManager, RouteManager)
  - Application coordinator (app.js)
- **APIs**:
  - Weather: `https://api.weather.gov`
  - Geocoding / Search: GeoApify (`geoapify.com`)
  - Routing: OpenRouteService (`openrouteservice.org`)
- **Build**: Gradle

## Getting Started

1. Ensure Java 17 is installed. Gradle wrapper is included in the project.
2. Set up PostgreSQL database with PostGIS extension:
   ```bash
   # Create database
   createdb tripweather
   
   # Enable PostGIS extension
   psql tripweather -c "CREATE EXTENSION postgis;"
   ```
3. Set up API keys as environment variables:
   ```bash
   # Source the provided script or set manually
   export OPENROUTESERVICE_API_KEY=your_openrouteservice_key
   export GEOAPIFY_API_KEY=your_geoapify_key
   ```
4. Configure database connection in `src/main/resources/application.properties`:
   ```properties
   spring.datasource.url=jdbc:postgresql://localhost:5432/tripweather
   spring.datasource.username=your_username
   spring.datasource.password=your_password
   ```
5. Run the application:
   ```bash
   ./gradlew bootRun
   ```
6. Open `http://localhost:8090` in a browser.

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
│   ├── WeatherService.js       # Weather API integration
│   └── RoutePersistenceService.js # Route save/load functionality
└── managers/                  # UI and feature managers
    ├── MapManager.js           # Map initialization and controls
    ├── WaypointManager.js      # Waypoint data management
    ├── WaypointRenderer.js     # Table rendering and marker management
    ├── SearchManager.js         # Location search functionality
    ├── UIManager.js            # UI overlays and interactions
    └── RouteManager.js         # Route calculation and display
```

## Database Schema

The application uses PostgreSQL with the following main entities:

- **Users**: Simple user management with guest user support
- **Routes**: Named collections of waypoints with creation timestamps
- **Waypoints**: Individual route points with coordinates, dates, times, and metadata

## API Endpoints

- `GET /` - Serves the main application page
- `GET /api/weather/forecast` - Get weather forecast for coordinates and time
- `GET /api/location/reverse` - Reverse geocode coordinates to location name
- `GET /api/location/search` - Search for locations by query
- `POST /api/route/calculate` - Calculate route between waypoints
- `POST /api/routes` - Save a route
- `GET /api/routes/{id}` - Load a route by ID
- `GET /api/routes/search` - Search routes by name

## Timezone Handling

The application provides comprehensive timezone support:

- Timezone information is automatically fetched from GeoApify when adding waypoints
- Supports both standard and daylight saving time offsets
- Automatic timezone abbreviation display based on date
- Proper time conversion when calculating arrival times across timezones
- Timezone data is stored with waypoints for persistence

## Route Calculation and Timing

Route calculation includes sophisticated timing features:

- Calculates optimal driving routes between consecutive waypoints
- Computes travel times and distances
- Adds waypoint durations to calculate departure times
- Handles timezone conversions for accurate arrival times
- Visualizes routes with colored polylines on the map
- Supports route recalculation when waypoints change

## Future Enhancements

1. **Export / Reporting**:
   - Generate PDF/HTML trip summaries with waypoints, dates, and weather
   - Printable map snapshot integration
   - Export route data to GPX format for GPS devices

2. **Advanced UI Features**:
   - Dark mode toggle with CSS custom properties
   - Enhanced mobile touch interactions
   - Keyboard shortcuts for common actions
   - Improved accessibility features

3. **Route Optimization Options**:
   - Multiple route preferences (fastest, shortest, scenic)
   - Route comparison interface
   - Alternative route suggestions
   - Avoidance options (tolls, highways, etc.)

4. **Advanced Weather Features**:
   - Historical weather data for planning
   - Weather alerts and warnings
   - Extended forecast support
   - Weather-based route recommendations

5. **Collaboration Features**:
   - Multi-user trip planning
   - Comment and annotation system
   - Trip sharing with permissions
   - Real-time collaboration

6. **Performance and Scalability**:
   - Caching improvements for weather and location data
   - Batch route calculations
   - Progressive web app (PWA) support
   - Offline functionality

## Development Notes

- The project uses a modular JavaScript architecture for maintainability
- All API calls include proper error handling and user feedback
- Timezone calculations are handled server-side to ensure consistency
- Database operations use transactions for data integrity
- The application supports both guest users and authenticated users

## Troubleshooting

- **API Key Issues**: Ensure both OPENROUTESERVICE_API_KEY and GEOAPIFY_API_KEY are set
- **Database Connection**: Verify PostgreSQL is running and PostGIS extension is enabled
- **Map Not Loading**: Check browser console for network issues or JavaScript errors
- **Weather Not Loading**: Verify coordinates are valid and weather.gov API is accessible

## License

This project is licensed under the MIT License.
