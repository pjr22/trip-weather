# Trip Weather - Implementation Plan

## Project Overview
Trip Weather is a Spring Boot web application that allows users to plot a driven route on a map with waypoints, add dates/times to each waypoint, and retrieve real-time weather forecast data from weather.gov for each location.

## Technology Stack
- **Backend**: Spring Boot 3.5.7 with Java 21
- **Frontend**: HTML, CSS, JavaScript
- **Mapping**: OpenStreetMap with Leaflet.js
- **Weather API**: weather.gov (National Weather Service API)

## Implementation Stages

### STAGE 1: Basic Map Display
**Goal**: Get the application running with a web page displaying a map centered on the user's current location.

**Tasks**:
1. Add Thymeleaf dependency for server-side template rendering (optional, can use static HTML)
2. Create a Spring MVC controller to serve the main page
3. Create an HTML page with:
   - OpenStreetMap integration using Leaflet.js
   - HTML5 Geolocation API to get user's current location
   - Map centered on the detected location (with fallback to default location)
4. Add CSS styling for the map container
5. Test the application runs and displays the map correctly

**Deliverables**:
- HomeController.java
- index.html (with Leaflet.js integration)
- CSS for map display

---

### STAGE 2: Waypoint Management
**Goal**: Add ability to add pins/waypoints to the map with a synchronized table display.

**Tasks**:
1. Implement click event handler on the map to add waypoints
2. Create a data structure to store waypoints (client-side JavaScript array)
3. Display waypoint markers on the map with custom icons/numbering
4. Create an HTML table below the map with columns:
   - Order Number (1, 2, 3, etc.)
   - Date (editable input field)
   - Time (editable input field)
   - Location Name (initially blank or coordinates, editable)
5. Add functionality to:
   - Add new waypoints by clicking on the map
   - Remove waypoints (with delete button in table)
   - Update waypoint order
   - Edit date, time, and location name in the table
6. Synchronize map markers with table rows (clicking marker highlights table row)
7. Add reverse geocoding (optional) to get location names from coordinates

**Deliverables**:
- Enhanced JavaScript for waypoint management
- HTML table structure
- Waypoint model (JavaScript class/object)
- CSS for table styling

---

### STAGE 3: Weather Forecast Integration
**Goal**: Retrieve and display weather information from weather.gov when date and time are entered.

**Tasks**:
1. **Backend Development**:
   - Create WeatherService.java to interact with weather.gov API
   - Implement two-step API call process:
     - GET `/points/{latitude},{longitude}` to get forecast URL
     - GET the forecast URL to retrieve weather data
   - Create REST endpoint in Spring Boot to handle weather requests
   - Parse weather.gov API response (JSON)
   - Extract relevant weather information:
     - Overall weather description
     - Weather icon URL
     - Temperature
     - Wind speed and direction
     - Precipitation probability
   - Handle API errors and edge cases (invalid coordinates, API unavailable)

2. **Frontend Development**:
   - Add weather data columns to the table:
     - Weather Condition
     - Weather Icon
     - Temperature
     - Wind Speed & Direction
     - Precipitation Probability
   - Add JavaScript to trigger weather API call when:
     - Date and time are entered/modified for a waypoint
     - A new waypoint is added with date/time already set
   - Display loading indicator while fetching weather data
   - Display weather information in the table
   - Handle errors gracefully (display error message if weather unavailable)

3. **Date/Time Handling**:
   - Validate that date/time is in the future (weather.gov provides forecasts)
   - Match forecast period to the entered date/time
   - Handle timezone considerations (weather.gov uses local timezone)

**Deliverables**:
- WeatherService.java
- WeatherController.java (REST endpoint)
- Weather model classes (WeatherData.java, WeatherResponse.java)
- Enhanced JavaScript for weather API calls
- Updated table with weather columns
- Error handling and user feedback

---

## API Documentation

### weather.gov API
The National Weather Service API (weather.gov) provides free weather forecasts.

**API Endpoints**:
1. Get grid endpoint: `https://api.weather.gov/points/{latitude},{longitude}`
   - Returns forecast URLs and metadata for the location
   
2. Get forecastHourly: `https://api.weather.gov/gridpoints/{office}/{gridX},{gridY}/forecast/hourly`
   - Returns detailed hourly forecast periods with weather conditions

**API Requirements**:
- User-Agent header must be set (e.g., "TripWeather/1.0 (contact@email.com)")
- Rate limiting: Reasonable use expected
- No API key required

**Response Data Structure**:
```json
{
  "properties": {
    "periods": [
      {
        "number": 1,
        "name": "",
        "startTime": "2025-11-01T12:00:00-05:00",
        "endTime": "2025-11-01T13:00:00-05:00",
        "isDaytime": true,
        "temperature": 43,
        "temperatureUnit": "F",
        "temperatureTrend": "",
        "probabilityOfPrecipitation": {
          "unitCode": "wmoUnit:percent",
          "value": 5
        },
        "dewpoint": {
          "unitCode": "wmoUnit:degC",
          "value": 0.5555555555555556
        },
        "relativeHumidity": {
          "unitCode": "wmoUnit:percent",
          "value": 67
        },
        "windSpeed": "10 mph",
        "windDirection": "N",
        "icon": "https://api.weather.gov/icons/land/day/bkn?size=small",
        "shortForecast": "Partly Sunny",
        "detailedForecast": ""
      }
    ]
  }
}
```

---

## Future Enhancements (Post-Stage 3)
1. Route drawing between waypoints (polylines on map)
2. Save/load routes (backend database integration)
3. Export route and weather data to PDF
4. Multi-day route planning
5. Weather alerts and warnings
6. Alternative weather data sources
7. Mobile responsive design improvements
8. Dark mode for map display

---

## Development Notes
- Use Spring Boot DevTools for hot reload during development
- Keep JavaScript modular and organized
- Add comprehensive error handling at each stage
- Test with various locations and time periods
- Consider adding unit tests for weather API integration
- Document API usage and rate limits
