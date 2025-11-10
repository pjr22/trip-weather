package com.pjr22.tripweather.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pjr22.tripweather.model.LocationData;
import com.pjr22.tripweather.model.RouteData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class RouteService {

    private final RestClient restClient;
    private final String apiKey;
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final LocationService locationService;
    
    private static final String DIRECTIONS_ENDPOINT = "/v2/directions/driving-car/geojson";

    /**
     * Get timezone information for a coordinate using LocationService
     */
    private String getTimezone(Double latitude, Double longitude) {
        try {
            LocationData locationData = locationService.reverseGeocode(latitude, longitude);
            if (locationData.getFeatures() != null && !locationData.getFeatures().isEmpty()) {
                LocationData.Timezone timezone = locationData.getFeatures().get(0).getProperties().getTimezone();
                if (timezone != null) {
                    return timezone.getName();
                }
            }
        } catch (Exception e) {
            System.err.println("Error getting timezone for coordinates " + latitude + ", " + longitude + ": " + e.getMessage());
        }
        return "UTC"; // Fallback
    }

    /**
     * Convert datetime string from one timezone to another using LocationService timezone data
     */
    private String convertDateTime(String dateTimeStr, String fromTimezone, String toTimezone) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            java.time.LocalDateTime localDateTime = java.time.LocalDateTime.parse(dateTimeStr, formatter);
            
            java.time.ZoneId fromZoneId = java.time.ZoneId.of(fromTimezone);
            java.time.ZoneId toZoneId = java.time.ZoneId.of(toTimezone);
            
            java.time.ZonedDateTime fromZonedDateTime = localDateTime.atZone(fromZoneId);
            java.time.ZonedDateTime toZonedDateTime = fromZonedDateTime.withZoneSameInstant(toZoneId);
            
            return toZonedDateTime.format(formatter);
        } catch (Exception e) {
            System.err.println("Error converting datetime from " + fromTimezone + " to " + toTimezone + ": " + e.getMessage());
            return dateTimeStr; // Return original if conversion fails
        }
    }

    /**
     * Add minutes to a datetime string in the specified timezone
     */
    private String addMinutesToDateTime(String dateTimeStr, String timezone, Integer minutes) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            java.time.LocalDateTime localDateTime = java.time.LocalDateTime.parse(dateTimeStr, formatter);
            
            java.time.ZoneId zoneId = java.time.ZoneId.of(timezone);
            java.time.ZonedDateTime zonedDateTime = localDateTime.atZone(zoneId);
            java.time.ZonedDateTime resultZonedDateTime = zonedDateTime.plusMinutes(minutes);
            
            return resultZonedDateTime.format(formatter);
        } catch (Exception e) {
            System.err.println("Error adding minutes to datetime: " + e.getMessage());
            return dateTimeStr; // Return original if addition fails
        }
    }

    public RouteService(@Value("${openrouteservice.api.key}") String apiKey, 
                       @Value("${openrouteservice.base.url:https://api.openrouteservice.org}") String baseUrl,
                       LocationService locationService) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.restClient = RestClient.builder()
                .baseUrl(this.baseUrl)
                .build();
        this.objectMapper = new ObjectMapper();
        this.locationService = locationService;
    }

    public RouteData calculateRoute(List<RouteRequest.Waypoint> waypoints) {
        return calculateRouteWithArrivalTimes(waypoints, null);
    }

    public RouteData calculateRouteWithArrivalTimes(List<RouteRequest.Waypoint> waypoints, String departureDateTime) {
        return calculateRouteWithArrivalTimesAndDurations(waypoints, departureDateTime, null);
    }

    public RouteData calculateRouteWithArrivalTimesAndDurations(List<RouteRequest.Waypoint> waypoints, String departureDateTime, List<Integer> durations) {
        try {
            if (apiKey == null || apiKey.isEmpty()) {
                return createErrorRoute("OpenRouteService API key not configured");
            }

            if (waypoints == null || waypoints.size() < 2) {
                return createErrorRoute("At least 2 waypoints are required for routing");
            }

            // Prepare request body for OpenRouteService
            RouteRequest request = new RouteRequest();
            request.setCoordinates(convertWaypointsToCoordinates(waypoints));
            
            String requestBody = objectMapper.writeValueAsString(request);

            JsonNode response = restClient.post()
                    .uri(DIRECTIONS_ENDPOINT + "?api_key=" + apiKey)
                    .header("Content-Type", "application/json")
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);

            return parseRouteResponseWithArrivalTimesAndDurations(response, waypoints, departureDateTime, durations);

        } catch (Exception e) {
            return createErrorRoute("Failed to calculate route: " + e.getMessage());
        }
    }

    private List<List<Double>> convertWaypointsToCoordinates(List<RouteRequest.Waypoint> waypoints) {
        List<List<Double>> coordinates = new ArrayList<>();
        for (RouteRequest.Waypoint waypoint : waypoints) {
            List<Double> coord = new ArrayList<>();
            coord.add(waypoint.getLongitude());
            coord.add(waypoint.getLatitude());
            coordinates.add(coord);
        }
        return coordinates;
    }

    private RouteData parseRouteResponseWithArrivalTimesAndDurations(JsonNode response, List<RouteRequest.Waypoint> originalWaypoints, String departureDateTime, List<Integer> durations) {
        try {
            // First, parse the basic route information (geometry, segments, etc.)
            JsonNode features = response.get("features");
            if (features == null || !features.isArray() || features.size() == 0) {
                return createErrorRoute("No features found in response");
            }

            JsonNode firstFeature = features.get(0);
            
            // Extract geometry from the feature
            JsonNode geometryNode = firstFeature.get("geometry");
            List<List<Double>> geometry = new ArrayList<>();
            if (geometryNode != null && geometryNode.has("coordinates")) {
                JsonNode coordinates = geometryNode.get("coordinates");
                if (coordinates.isArray()) {
                    for (JsonNode coord : coordinates) {
                        if (coord.isArray() && coord.size() >= 2) {
                            List<Double> point = new ArrayList<>();
                            point.add(coord.get(0).asDouble()); // longitude
                            point.add(coord.get(1).asDouble()); // latitude
                            geometry.add(point);
                        }
                    }
                }
            }

            // Extract summary from properties
            JsonNode properties = firstFeature.get("properties");
            Double distance = null;
            Double duration = null;
            List<RouteData.RouteSegment> segments = new ArrayList<>();
            
            if (properties != null) {
                // Extract summary
                JsonNode summary = properties.get("summary");
                if (summary != null) {
                    if (summary.has("distance")) {
                        distance = summary.get("distance").asDouble();
                    }
                    if (summary.has("duration")) {
                        duration = summary.get("duration").asDouble();
                    }
                }
                
                // Extract segments
                JsonNode segmentsNode = properties.get("segments");
                if (segmentsNode != null && segmentsNode.isArray()) {
                    for (JsonNode segmentNode : segmentsNode) {
                        RouteData.RouteSegment segment = new RouteData.RouteSegment();
                        if (segmentNode.has("distance")) {
                            segment.setDistance(segmentNode.get("distance").asDouble());
                        }
                        if (segmentNode.has("duration")) {
                            segment.setDuration(segmentNode.get("duration").asDouble());
                        }
                        segments.add(segment);
                    }
                }
            }

            // Create waypoint information with arrival times, durations, and timezones
            List<RouteData.WaypointCoordinates> waypointInfo = new ArrayList<>();
            if (departureDateTime != null && !departureDateTime.isEmpty()) {
                // Calculate arrival times with durations and timezone support
                waypointInfo = calculateArrivalTimesWithDurationAndTimezone(originalWaypoints, segments, departureDateTime, durations);
            } else {
                // No arrival times needed
                for (RouteRequest.Waypoint wp : originalWaypoints) {
                    List<Double> location = List.of(wp.getLongitude(), wp.getLatitude());
                    RouteData.WaypointCoordinates waypoint = new RouteData.WaypointCoordinates(location, wp.getName());
                    // Add timezone even if no departure time is set
                    String timezone = getTimezone(wp.getLatitude(), wp.getLongitude());
                    waypoint.setTimezone(timezone);
                    waypointInfo.add(waypoint);
                }
            }

            RouteData routeData = new RouteData();
            routeData.setGeometry(geometry);
            routeData.setDistance(distance);
            routeData.setDuration(duration);
            routeData.setSegments(segments);
            routeData.setWaypoints(waypointInfo);

            return routeData;
            
        } catch (Exception e) {
            return createErrorRoute("Failed to parse route response with arrival times: " + e.getMessage());
        }
    }
    
    private List<RouteData.WaypointCoordinates> calculateArrivalTimesWithDurationAndTimezone(
            List<RouteRequest.Waypoint> originalWaypoints, 
            List<RouteData.RouteSegment> segments, 
            String departureDateTime,
            List<Integer> durations) {
        
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            String currentTimeStr = departureDateTime;
            
            List<RouteData.WaypointCoordinates> waypointsWithTimes = new ArrayList<>();
            
            for (int i = 0; i < originalWaypoints.size(); i++) {
                RouteRequest.Waypoint originalWaypoint = originalWaypoints.get(i);
                
                // Get timezone for this waypoint - use provided timezone name if available, otherwise look it up
                String timezone = originalWaypoint.getTimezoneName() != null && !originalWaypoint.getTimezoneName().isEmpty()
                    ? originalWaypoint.getTimezoneName()
                    : getTimezone(originalWaypoint.getLatitude(), originalWaypoint.getLongitude());
                
                // Create waypoint coordinates with location and name
                List<Double> location = List.of(originalWaypoint.getLongitude(), originalWaypoint.getLatitude());
                RouteData.WaypointCoordinates waypoint = new RouteData.WaypointCoordinates(location, originalWaypoint.getName());
                waypoint.setTimezone(timezone);
                
                // Set duration for this waypoint (default to 0 if not provided)
                Integer waypointDuration = 0;
                if (durations != null && i < durations.size()) {
                    waypointDuration = durations.get(i) != null ? durations.get(i) : 0;
                }
                waypoint.setDuration(waypointDuration);
                
                if (i == 0) {
                    // First waypoint gets the departure time
                    waypoint.setArrivalTime(currentTimeStr);
                } else {
                    // Convert previous waypoint's departure time to current waypoint's timezone
                    RouteRequest.Waypoint previousWaypoint = originalWaypoints.get(i - 1);
                    String previousTimezone = previousWaypoint.getTimezoneName() != null && !previousWaypoint.getTimezoneName().isEmpty()
                        ? previousWaypoint.getTimezoneName()
                        : getTimezone(previousWaypoint.getLatitude(), previousWaypoint.getLongitude());
                    
                    String arrivalTimeInCurrentTimezone = convertDateTime(currentTimeStr, previousTimezone, timezone);
                    waypoint.setArrivalTime(arrivalTimeInCurrentTimezone);
                    currentTimeStr = arrivalTimeInCurrentTimezone;
                }
                
                // Calculate departure time (arrival time + duration)
                String departureTime = addMinutesToDateTime(currentTimeStr, timezone, waypointDuration);
                waypoint.setDepartureTime(departureTime);
                
                // Update current time for next segment (convert to next waypoint's timezone when we get there)
                if (i < originalWaypoints.size() - 1) {
                    // Add travel time for next segment
                    if (i < segments.size()) {
                        RouteData.RouteSegment segment = segments.get(i);
                        if (segment.getDuration() != null) {
                            // Add travel time (duration is in seconds)
                            currentTimeStr = addMinutesToDateTime(departureTime, timezone, (int)(segment.getDuration().longValue() / 60));
                        }
                    }
                }
                
                waypointsWithTimes.add(waypoint);
            }
            
            return waypointsWithTimes;
            
        } catch (Exception e) {
            // If there's an error parsing the departure time, return waypoints without arrival times
            System.err.println("Error calculating arrival times with durations and timezones: " + e.getMessage());
            // Return basic waypoints without arrival times
            List<RouteData.WaypointCoordinates> fallbackWaypoints = new ArrayList<>();
            for (RouteRequest.Waypoint wp : originalWaypoints) {
                List<Double> location = List.of(wp.getLongitude(), wp.getLatitude());
                RouteData.WaypointCoordinates waypoint = new RouteData.WaypointCoordinates(location, wp.getName());
                String timezone = wp.getTimezoneName() != null && !wp.getTimezoneName().isEmpty()
                    ? wp.getTimezoneName()
                    : getTimezone(wp.getLatitude(), wp.getLongitude());
                waypoint.setTimezone(timezone);
                fallbackWaypoints.add(waypoint);
            }
            return fallbackWaypoints;
        }
    }

    private RouteData createErrorRoute(String errorMessage) {
        RouteData errorRoute = new RouteData();
        errorRoute.setGeometry(new ArrayList<>());
        errorRoute.setDistance(0.0);
        errorRoute.setDuration(0.0);
        // We could add error information to the model, but for now, the frontend will handle the error case
        return errorRoute;
    }

    // Request model for OpenRouteService API
    public static class RouteRequest {
        private List<List<Double>> coordinates;
        private String format = "geojson";
        
        public List<List<Double>> getCoordinates() {
            return coordinates;
        }
        
        public void setCoordinates(List<List<Double>> coordinates) {
            this.coordinates = coordinates;
        }
        
        public String getFormat() {
            return format;
        }
        
        public void setFormat(String format) {
            this.format = format;
        }

        public static class Waypoint {
            private Double latitude;
            private Double longitude;
            private String name;
            private String timezoneName;

            public Waypoint() {}

            public Waypoint(Double latitude, Double longitude, String name) {
                this.latitude = latitude;
                this.longitude = longitude;
                this.name = name;
            }
            
            public Waypoint(Double latitude, Double longitude, String name, String timezoneName) {
                this.latitude = latitude;
                this.longitude = longitude;
                this.name = name;
                this.timezoneName = timezoneName;
            }

            public Double getLatitude() {
                return latitude;
            }

            public void setLatitude(Double latitude) {
                this.latitude = latitude;
            }

            public Double getLongitude() {
                return longitude;
            }

            public void setLongitude(Double longitude) {
                this.longitude = longitude;
            }

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }
            
            public String getTimezoneName() {
                return timezoneName;
            }

            public void setTimezoneName(String timezoneName) {
                this.timezoneName = timezoneName;
            }
        }
    }
}
