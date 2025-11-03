package com.pjr22.tripweather.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pjr22.tripweather.model.RouteData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class RouteService {

    private final RestClient restClient;
    private final String apiKey;
    private final ObjectMapper objectMapper;
    
    private static final String BASE_URL = "https://api.openrouteservice.org";
    private static final String DIRECTIONS_ENDPOINT = "/v2/directions/driving-car/geojson";

    public RouteService(@Value("${openrouteservice.api.key}") String apiKey) {
        this.apiKey = apiKey;
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public RouteData calculateRoute(List<RouteRequest.Waypoint> waypoints) {
        return calculateRouteWithArrivalTimes(waypoints, null);
    }

    public RouteData calculateRouteWithArrivalTimes(List<RouteRequest.Waypoint> waypoints, String departureDateTime) {
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

            return parseRouteResponseWithArrivalTimes(response, waypoints, departureDateTime);

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

    private RouteData parseRouteResponse(JsonNode response, List<RouteRequest.Waypoint> originalWaypoints) {
        try {
            // OpenRouteService returns a GeoJSON FeatureCollection
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

            // Create waypoint information
            List<RouteData.WaypointCoordinates> waypointInfo = new ArrayList<>();
            for (RouteRequest.Waypoint wp : originalWaypoints) {
                List<Double> location = List.of(wp.getLongitude(), wp.getLatitude());
                waypointInfo.add(new RouteData.WaypointCoordinates(location, wp.getName()));
            }

            RouteData routeData = new RouteData();
            routeData.setGeometry(geometry);
            routeData.setDistance(distance);
            routeData.setDuration(duration);
            routeData.setSegments(segments);
            routeData.setWaypoints(waypointInfo);

            return routeData;

        } catch (Exception e) {
            return createErrorRoute("Failed to parse route response: " + e.getMessage());
        }
    }

    private RouteData parseRouteResponseWithArrivalTimes(JsonNode response, List<RouteRequest.Waypoint> originalWaypoints, String departureDateTime) {
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

            // Create waypoint information with arrival times
            List<RouteData.WaypointCoordinates> waypointInfo = new ArrayList<>();
            if (departureDateTime != null && !departureDateTime.isEmpty()) {
                // Calculate arrival times
                waypointInfo = calculateArrivalTimes(originalWaypoints, segments, departureDateTime);
            } else {
                // No arrival times needed
                for (RouteRequest.Waypoint wp : originalWaypoints) {
                    List<Double> location = List.of(wp.getLongitude(), wp.getLatitude());
                    waypointInfo.add(new RouteData.WaypointCoordinates(location, wp.getName()));
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
    
    private List<RouteData.WaypointCoordinates> calculateArrivalTimes(
            List<RouteRequest.Waypoint> originalWaypoints, 
            List<RouteData.RouteSegment> segments, 
            String departureDateTime) {
        
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            LocalDateTime currentTime = LocalDateTime.parse(departureDateTime, formatter);
            
            List<RouteData.WaypointCoordinates> waypointsWithTimes = new ArrayList<>();
            
            for (int i = 0; i < originalWaypoints.size(); i++) {
                RouteRequest.Waypoint originalWaypoint = originalWaypoints.get(i);
                
                // Create waypoint coordinates with location and name
                List<Double> location = List.of(originalWaypoint.getLongitude(), originalWaypoint.getLatitude());
                RouteData.WaypointCoordinates waypoint = new RouteData.WaypointCoordinates(location, originalWaypoint.getName());
                
                if (i == 0) {
                    // First waypoint gets the departure time
                    waypoint.setArrivalTime(departureDateTime);
                } else {
                    // Subsequent waypoints get arrival times based on travel time
                    if (i - 1 < segments.size()) {
                        RouteData.RouteSegment segment = segments.get(i - 1);
                        if (segment.getDuration() != null) {
                            // Add travel time (duration is in seconds)
                            currentTime = currentTime.plusSeconds(segment.getDuration().longValue());
                        }
                    }
                    waypoint.setArrivalTime(currentTime.format(formatter));
                }
                
                waypointsWithTimes.add(waypoint);
            }
            
            return waypointsWithTimes;
            
        } catch (Exception e) {
            // If there's an error parsing the departure time, return waypoints without arrival times
            System.err.println("Error calculating arrival times: " + e.getMessage());
            // Return basic waypoints without arrival times
            List<RouteData.WaypointCoordinates> fallbackWaypoints = new ArrayList<>();
            for (RouteRequest.Waypoint wp : originalWaypoints) {
                List<Double> location = List.of(wp.getLongitude(), wp.getLatitude());
                fallbackWaypoints.add(new RouteData.WaypointCoordinates(location, wp.getName()));
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

            public Waypoint() {}

            public Waypoint(Double latitude, Double longitude, String name) {
                this.latitude = latitude;
                this.longitude = longitude;
                this.name = name;
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
        }
    }
}
