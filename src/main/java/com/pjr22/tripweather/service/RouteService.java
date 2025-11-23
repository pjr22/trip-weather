package com.pjr22.tripweather.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pjr22.tripweather.Utils;
import com.pjr22.tripweather.model.LocationData;
import com.pjr22.tripweather.model.RouteData;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class RouteService {

   private final RestClient restClient;
   private final String apiKey;
   private final String baseUrl;
   private final ObjectMapper objectMapper;

   private static final String DIRECTIONS_ENDPOINT = "/v2/directions/driving-car/geojson";
   private static final String ELEVATION_ENDPOINT = "/elevation/point";
   private static final String SNAP_ENDPOINT = "/v2/snap/driving-car/geojson";

   public RouteService(
         @Value("${openrouteservice.api.key}") String apiKey,
         @Value("${openrouteservice.base.url:https://api.openrouteservice.org}") String baseUrl
   ) {
      this.apiKey = apiKey;
      this.baseUrl = baseUrl;
      this.restClient = RestClient.builder().baseUrl(this.baseUrl).build();
      this.objectMapper = new ObjectMapper();
   }

   public LocationData snapToLocation(double latitude, double longitude) {
      try {
          if (apiKey == null || apiKey.isEmpty()) {
              return null;
          }
          
          // {"locations":[[8.669629,49.413025],[8.675841,49.418532],[8.665144,49.415594]],"radius":350}'
          Map<String, Object> body = new HashMap<>();
          body.put("locations", List.of(List.of(longitude, latitude)));
          body.put("radius", Integer.valueOf(10000));

          LocationData locationData = restClient.post()
                  .uri(SNAP_ENDPOINT)
                  .body(body)
                  .header("Authorization", apiKey)
                  .header("Content-Type", "application/json")
                  .retrieve()
                  .toEntity(LocationData.class)
                  .getBody();

          return locationData;
       } catch (Exception e) {
          log.info("Failed to get snap location info from: {}", SNAP_ENDPOINT);
          log.error("Snap request failed.", e);
          return null;
      }
   }

   // https://localhost:5000/elevation/point?geometry=13.349762,38.11295
   public Double getElevation(double latitude, double longitude) {
      try {
         if (apiKey == null || apiKey.isEmpty()) {
            return null;
        }

        String url = String.format(ELEVATION_ENDPOINT + "?geometry=%s,%s", longitude, latitude);
        LocationData.Feature feature = restClient.get()
              .uri(url)
              .header("Authorization", apiKey)
              .retrieve()
              .body(LocationData.Feature.class);
        
        return Double.valueOf(feature.getGeometry().getCoordinates().get(2));
        
      } catch (Exception e) {
         return null;
      }
   }

   public RouteData calculateRoute(
         List<RouteRequest.Waypoint> waypoints,
         ZonedDateTime departureDateTime,
         List<Integer> durations
   ) {
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
         request.setRadiuses(List.of(-1));
         request.setElevation(true);

         String requestBody = objectMapper.writeValueAsString(request);

         JsonNode response = restClient.post()
               .uri(DIRECTIONS_ENDPOINT)
               .header("Authorization", apiKey)
               .header("Content-Type", "application/json")
               .body(requestBody)
               .retrieve()
               .body(JsonNode.class);
         
         ZonedDateTime now = ZonedDateTime.now(departureDateTime.getZone());
         if (departureDateTime.isBefore(now)) {
            departureDateTime = now;
         }

         return parseRouteResponseWithArrivalTimesAndDurations(response, waypoints, departureDateTime, durations);

      } catch (Exception e) {
         return createErrorRoute("Failed to calculate route: " + e.getMessage());
      }
   }

   /**
    * Add minutes to a datetime string in the specified timezone
    */
   private String addMinutesToDateTime(String dateTimeStr, String timezone, Integer minutes) {
      try {
         DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
         LocalDateTime localDateTime = LocalDateTime.parse(dateTimeStr, formatter);

         ZoneId zoneId = ZoneId.of(timezone);
         ZonedDateTime zonedDateTime = localDateTime.atZone(zoneId);
         ZonedDateTime resultZonedDateTime = zonedDateTime.plusMinutes(minutes);

         return resultZonedDateTime.format(formatter);
      } catch (Exception e) {
         System.err.println("Error adding minutes to datetime: " + e.getMessage());
         return dateTimeStr; // Return original if addition fails
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

   private RouteData parseRouteResponseWithArrivalTimesAndDurations(JsonNode response,
         List<RouteRequest.Waypoint> originalWaypoints, ZonedDateTime departureDateTime, List<Integer> durations) {
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
                  if (coord.isArray() && coord.size() > 1) {
                     List<Double> point = new ArrayList<>();
                     point.add(coord.get(0).asDouble()); // longitude
                     point.add(coord.get(1).asDouble()); // latitude
                     if (coord.size() > 2) {
                        point.add(coord.get(2).asDouble()); // elevation)
                     }
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
         if (departureDateTime != null) {
            // Calculate arrival times with durations and timezone support
            waypointInfo = calculateArrivalTimesWithDurationAndTimezone(originalWaypoints, segments, departureDateTime,
                  durations);
         } else {
            // No arrival times needed
            for (RouteRequest.Waypoint wp : originalWaypoints) {
               List<Double> location = List.of(wp.getLongitude(), wp.getLatitude());
               RouteData.WaypointCoordinates waypoint = new RouteData.WaypointCoordinates(location, wp.getName());
               // Add timezone even if no departure time is set
               String timezone = wp.getTimezoneName();
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
         List<RouteRequest.Waypoint> originalWaypoints, List<RouteData.RouteSegment> segments, ZonedDateTime departureDateTime,
         List<Integer> durations) {

      try {
         DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
         String currentTimeStr = departureDateTime.format(formatter);

         List<RouteData.WaypointCoordinates> waypointsWithTimes = new ArrayList<>();

         for (int i = 0; i < originalWaypoints.size(); i++) {
            RouteRequest.Waypoint originalWaypoint = originalWaypoints.get(i);
            String timezone = originalWaypoint.getTimezoneName();

            // Create waypoint coordinates with location and name
            List<Double> location = List.of(originalWaypoint.getLongitude(), originalWaypoint.getLatitude());
            RouteData.WaypointCoordinates waypoint = new RouteData.WaypointCoordinates(location,
                  originalWaypoint.getName());
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
               String previousTimezone = previousWaypoint.getTimezoneName();

               String arrivalTimeInCurrentTimezone = Utils.convertDateTime(currentTimeStr, previousTimezone, timezone);
               waypoint.setArrivalTime(arrivalTimeInCurrentTimezone);
               currentTimeStr = arrivalTimeInCurrentTimezone;
            }

            // Calculate departure time (arrival time + duration)
            String departureTime = addMinutesToDateTime(currentTimeStr, timezone, waypointDuration);
            waypoint.setDepartureTime(departureTime);

            // Update current time for next segment (convert to next waypoint's timezone
            // when we get there)
            if (i < originalWaypoints.size() - 1) {
               // Add travel time for next segment
               if (i < segments.size()) {
                  RouteData.RouteSegment segment = segments.get(i);
                  if (segment.getDuration() != null) {
                     // Add travel time (duration is in seconds)
                     currentTimeStr = addMinutesToDateTime(departureTime, timezone,
                           (int) (segment.getDuration().longValue() / 60));
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
            String timezone = wp.getTimezoneName();
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
      // We could add error information to the model, but for now, the frontend will
      // handle the error case
      return errorRoute;
   }

   /**
    *  Request model for OpenRouteService API
    */
   @Setter
   @Getter
   @NoArgsConstructor
   @AllArgsConstructor
   public static class RouteRequest {
      private String format = "geojson";
      private List<List<Double>> coordinates;
      private List<Integer> radiuses;
      private Boolean elevation;

      @Setter
      @Getter
      @NoArgsConstructor
      @AllArgsConstructor
      public static class Waypoint {
         private Double latitude;
         private Double longitude;
         private String name;
         private String timezoneName;
      }
   }
}
