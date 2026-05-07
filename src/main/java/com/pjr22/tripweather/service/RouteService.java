package com.pjr22.tripweather.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pjr22.tripweather.Utils;
import com.pjr22.tripweather.model.LocationData;
import com.pjr22.tripweather.model.OrsResponseCache;
import com.pjr22.tripweather.model.RouteData;
import com.pjr22.tripweather.repository.OrsResponseCacheRepository;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Wraps OpenRouteService with a Postgres-backed durable response cache. Each
 * of directions / snap / elevation hashes a canonical request payload (with
 * coordinates rounded to ~10 cm precision) and looks the result up in
 * {@code ors_response_cache}. On a hit within TTL, no upstream call. On a
 * miss or stale entry, calls upstream; on upstream failure with a stale
 * entry within the per-endpoint stale-max window, serves stale.
 *
 * Per-endpoint TTLs differ because routes can subtly change with road network
 * updates while snap+elevation are essentially geological.
 */
@Slf4j
@Service
public class RouteService {

   private final RestClient restClient;
   private final String apiKey;
   private final ObjectMapper objectMapper;
   private final OrsResponseCacheRepository cacheRepository;
   private final Clock clock;
   private final long directionsTtlHours;
   private final long directionsStaleMaxHours;
   private final long snapTtlHours;
   private final long snapStaleMaxHours;
   private final long elevationTtlHours;
   private final long elevationStaleMaxHours;

   private static final String DIRECTIONS_ENDPOINT = "/v2/directions/driving-car/geojson";
   private static final String ELEVATION_ENDPOINT = "/elevation/point";
   private static final String SNAP_ENDPOINT = "/v2/snap/driving-car/geojson";

   private static final String CACHE_KIND_DIRECTIONS = "directions";
   private static final String CACHE_KIND_SNAP = "snap";
   private static final String CACHE_KIND_ELEVATION = "elevation";

   /** Bump if the canonical request shape (or upstream response shape) changes
    *  in a way that invalidates previously-cached entries. */
   private static final int CACHE_KEY_VERSION = 1;

   private static final int SNAP_RADIUS_METERS = 10_000;
   private static final int COORDINATE_DECIMALS = 6;

   public RouteService(
         @Value("${openrouteservice.api.key}") String apiKey,
         RestClient orsRestClient,
         ObjectMapper objectMapper,
         OrsResponseCacheRepository cacheRepository,
         Clock clock,
         @Value("${trip.routing.directions-ttl-hours:24}") long directionsTtlHours,
         @Value("${trip.routing.directions-stale-max-hours:168}") long directionsStaleMaxHours,
         @Value("${trip.routing.snap-ttl-hours:720}") long snapTtlHours,
         @Value("${trip.routing.snap-stale-max-hours:2160}") long snapStaleMaxHours,
         @Value("${trip.routing.elevation-ttl-hours:720}") long elevationTtlHours,
         @Value("${trip.routing.elevation-stale-max-hours:2160}") long elevationStaleMaxHours
   ) {
      this.apiKey = apiKey;
      this.restClient = orsRestClient;
      this.objectMapper = objectMapper;
      this.cacheRepository = cacheRepository;
      this.clock = clock;
      this.directionsTtlHours = directionsTtlHours;
      this.directionsStaleMaxHours = directionsStaleMaxHours;
      this.snapTtlHours = snapTtlHours;
      this.snapStaleMaxHours = snapStaleMaxHours;
      this.elevationTtlHours = elevationTtlHours;
      this.elevationStaleMaxHours = elevationStaleMaxHours;
   }

   public LocationData snapToLocation(double latitude, double longitude) {
      if (apiKey == null || apiKey.isEmpty()) {
         return null;
      }
      try {
         String hash = snapCacheKey(latitude, longitude);
         JsonNode response = getOrFetchCached(hash, CACHE_KIND_SNAP,
               snapTtlHours, snapStaleMaxHours,
               () -> callSnapApi(latitude, longitude));
         if (response == null) {
            return null;
         }
         return objectMapper.treeToValue(response, LocationData.class);
      } catch (Exception e) {
         log.warn("Snap request failed for ({}, {}): {}", latitude, longitude, e.getMessage());
         return null;
      }
   }

   public Double getElevation(double latitude, double longitude) {
      if (apiKey == null || apiKey.isEmpty()) {
         return null;
      }
      try {
         String hash = elevationCacheKey(latitude, longitude);
         JsonNode response = getOrFetchCached(hash, CACHE_KIND_ELEVATION,
               elevationTtlHours, elevationStaleMaxHours,
               () -> callElevationApi(latitude, longitude));
         if (response == null) {
            return null;
         }
         JsonNode coords = response.path("geometry").path("coordinates");
         if (coords.isArray() && coords.size() >= 3 && !coords.get(2).isNull()) {
            return coords.get(2).asDouble();
         }
         return null;
      } catch (Exception e) {
         log.warn("Failed to get elevation for ({}, {}): {}",
               latitude, longitude, e.getMessage());
         return null;
      }
   }

   public RouteData calculateRoute(
         List<RouteRequest.Waypoint> waypoints,
         ZonedDateTime departureDateTime,
         List<Integer> durations
   ) {
      if (apiKey == null || apiKey.isEmpty()) {
         return createErrorRoute("OpenRouteService API key not configured");
      }
      if (waypoints == null || waypoints.size() < 2) {
         return createErrorRoute("At least 2 waypoints are required for routing");
      }

      JsonNode response;
      try {
         String hash = directionsCacheKey(waypoints);
         response = getOrFetchCached(hash, CACHE_KIND_DIRECTIONS,
               directionsTtlHours, directionsStaleMaxHours,
               () -> callDirectionsApi(waypoints));
      } catch (Exception e) {
         return createErrorRoute("Failed to calculate route: " + e.getMessage());
      }
      if (response == null) {
         return createErrorRoute("No directions response");
      }

      ZonedDateTime now = ZonedDateTime.now(departureDateTime.getZone());
      if (departureDateTime.isBefore(now)) {
         departureDateTime = now;
      }
      return parseRouteResponseWithArrivalTimesAndDurations(response, waypoints, departureDateTime, durations);
   }

   // ---------------------------------------------------------------- caching

   /** SAM type so endpoint methods can supply their upstream call as a lambda. */
   @FunctionalInterface
   private interface ApiCall {
      JsonNode execute() throws Exception;
   }

   private JsonNode getOrFetchCached(String requestHash, String endpoint,
                                     long ttlHours, long staleMaxHours,
                                     ApiCall apiCall) throws Exception {
      Optional<OrsResponseCache> cached = cacheRepository.findById(requestHash);
      LocalDateTime now = LocalDateTime.now(clock);

      if (cached.isPresent()) {
         OrsResponseCache entry = cached.get();
         if (now.isBefore(entry.getFetchedAt().plusHours(ttlHours))) {
            return parseJsonOrNull(entry.getResponseJson());
         }
         try {
            JsonNode fresh = apiCall.execute();
            if (fresh != null) {
               persist(requestHash, endpoint, fresh, now);
               return fresh;
            }
         } catch (Exception e) {
            if (now.isBefore(entry.getFetchedAt().plusHours(staleMaxHours))) {
               log.warn("ORS {} refresh failed; serving stale cached entry from {}",
                     endpoint, entry.getFetchedAt(), e);
               return parseJsonOrNull(entry.getResponseJson());
            }
            throw e;
         }
         // apiCall returned null without throwing — fall back to stale within window
         if (now.isBefore(entry.getFetchedAt().plusHours(staleMaxHours))) {
            log.warn("ORS {} returned null; serving stale cached entry from {}",
                  endpoint, entry.getFetchedAt());
            return parseJsonOrNull(entry.getResponseJson());
         }
         return null;
      }

      JsonNode fresh = apiCall.execute();
      if (fresh != null) {
         persist(requestHash, endpoint, fresh, now);
      }
      return fresh;
   }

   private void persist(String hash, String endpoint, JsonNode response, LocalDateTime fetchedAt) {
      try {
         OrsResponseCache entry = new OrsResponseCache(hash, endpoint,
               objectMapper.writeValueAsString(response), fetchedAt);
         cacheRepository.save(entry);
      } catch (Exception e) {
         log.warn("Failed to persist ORS {} cache entry", endpoint, e);
      }
   }

   private JsonNode parseJsonOrNull(String json) {
      try {
         return objectMapper.readTree(json);
      } catch (Exception e) {
         log.warn("Could not parse cached ORS response", e);
         return null;
      }
   }

   private String directionsCacheKey(List<RouteRequest.Waypoint> waypoints) {
      List<List<Double>> rounded = new ArrayList<>(waypoints.size());
      for (RouteRequest.Waypoint wp : waypoints) {
         rounded.add(List.of(round(wp.getLongitude()), round(wp.getLatitude())));
      }
      Map<String, Object> canonical = new LinkedHashMap<>();
      canonical.put("endpoint", CACHE_KIND_DIRECTIONS);
      canonical.put("version", CACHE_KEY_VERSION);
      canonical.put("coordinates", rounded);
      return sha256Hex(canonicalJson(canonical));
   }

   private String snapCacheKey(double latitude, double longitude) {
      return pointCacheKey(CACHE_KIND_SNAP, latitude, longitude);
   }

   private String elevationCacheKey(double latitude, double longitude) {
      return pointCacheKey(CACHE_KIND_ELEVATION, latitude, longitude);
   }

   private String pointCacheKey(String endpoint, double latitude, double longitude) {
      Map<String, Object> canonical = new LinkedHashMap<>();
      canonical.put("endpoint", endpoint);
      canonical.put("version", CACHE_KEY_VERSION);
      canonical.put("lat", round(latitude));
      canonical.put("lon", round(longitude));
      return sha256Hex(canonicalJson(canonical));
   }

   private static double round(double v) {
      double scale = Math.pow(10, COORDINATE_DECIMALS);
      return Math.round(v * scale) / scale;
   }

   private String canonicalJson(Object obj) {
      try {
         return objectMapper.copy()
               .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
               .writeValueAsString(obj);
      } catch (Exception e) {
         throw new RuntimeException("Failed to serialize canonical cache key", e);
      }
   }

   private static String sha256Hex(String input) {
      try {
         MessageDigest md = MessageDigest.getInstance("SHA-256");
         byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
         StringBuilder sb = new StringBuilder(64);
         for (byte b : digest) {
            sb.append(String.format("%02x", b));
         }
         return sb.toString();
      } catch (NoSuchAlgorithmException e) {
         throw new RuntimeException(e);
      }
   }

   // ----------------------------------------------------------- API plumbing

   private JsonNode callDirectionsApi(List<RouteRequest.Waypoint> waypoints) throws Exception {
      RouteRequest request = new RouteRequest();
      request.setCoordinates(convertWaypointsToCoordinates(waypoints));
      request.setRadiuses(List.of(-1));
      request.setElevation(true);
      request.setInstructions(true);
      request.setInstructionsFormat("text");
      request.setLanguage("en");

      String requestBody = objectMapper.writeValueAsString(request);
      return restClient.post()
            .uri(DIRECTIONS_ENDPOINT)
            .header("Authorization", apiKey)
            .header("Content-Type", "application/json")
            .body(requestBody)
            .retrieve()
            .body(JsonNode.class);
   }

   private JsonNode callSnapApi(double latitude, double longitude) {
      Map<String, Object> body = new HashMap<>();
      body.put("locations", List.of(List.of(longitude, latitude)));
      body.put("radius", Integer.valueOf(SNAP_RADIUS_METERS));
      return restClient.post()
            .uri(SNAP_ENDPOINT)
            .body(body)
            .header("Authorization", apiKey)
            .header("Content-Type", "application/json")
            .retrieve()
            .body(JsonNode.class);
   }

   private JsonNode callElevationApi(double latitude, double longitude) {
      String url = String.format(ELEVATION_ENDPOINT + "?geometry=%s,%s", longitude, latitude);
      return restClient.get()
            .uri(url)
            .header("Authorization", apiKey)
            .retrieve()
            .body(JsonNode.class);
   }

   // ----------------------------------------------- response parsing helpers

   /**
    * Add minutes to a datetime string in the specified timezone. Falls back to
    * the application's default timezone when the supplied one is null or blank
    * (e.g. on the synthetic two-waypoint connector requests the navigation
    * client sends, where arrival-time fields are unused).
    */
   private String addMinutesToDateTime(String dateTimeStr, String timezone, Integer minutes) {
      try {
         DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
         LocalDateTime localDateTime = LocalDateTime.parse(dateTimeStr, formatter);

         String resolvedTimezone = (timezone == null || timezone.isBlank())
               ? Utils.default_timezone_name : timezone;
         ZoneId zoneId = ZoneId.of(resolvedTimezone);
         ZonedDateTime zonedDateTime = localDateTime.atZone(zoneId);
         ZonedDateTime resultZonedDateTime = zonedDateTime.plusMinutes(minutes);

         return resultZonedDateTime.format(formatter);
      } catch (Exception e) {
         log.error("Error adding minutes to datetime: {}", dateTimeStr, e);
         return dateTimeStr;
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
         JsonNode features = response.get("features");
         if (features == null || !features.isArray() || features.size() == 0) {
            return createErrorRoute("No features found in response");
         }

         JsonNode firstFeature = features.get(0);

         JsonNode geometryNode = firstFeature.get("geometry");
         List<List<Double>> geometry = new ArrayList<>();
         if (geometryNode != null && geometryNode.has("coordinates")) {
            JsonNode coordinates = geometryNode.get("coordinates");
            if (coordinates.isArray()) {
               for (JsonNode coord : coordinates) {
                  if (coord.isArray() && coord.size() > 1) {
                     List<Double> point = new ArrayList<>();
                     point.add(coord.get(0).asDouble());
                     point.add(coord.get(1).asDouble());
                     if (coord.size() > 2) {
                        point.add(coord.get(2).asDouble());
                     }
                     geometry.add(point);
                  }
               }
            }
         }

         JsonNode properties = firstFeature.get("properties");
         Double distance = null;
         Double duration = null;
         List<RouteData.RouteSegment> segments = new ArrayList<>();

         if (properties != null) {
            JsonNode summary = properties.get("summary");
            if (summary != null) {
               if (summary.has("distance")) {
                  distance = summary.get("distance").asDouble();
               }
               if (summary.has("duration")) {
                  duration = summary.get("duration").asDouble();
               }
            }

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
                  if (segmentNode.has("steps")) {
                     segment.setSteps(parseSteps(segmentNode.get("steps")));
                  }
                  segments.add(segment);
               }
            }
         }

         List<RouteData.WaypointCoordinates> waypointInfo = new ArrayList<>();
         if (departureDateTime != null) {
            waypointInfo = calculateArrivalTimesWithDurationAndTimezone(originalWaypoints, segments, departureDateTime,
                  durations);
         } else {
            for (RouteRequest.Waypoint wp : originalWaypoints) {
               List<Double> location = List.of(wp.getLongitude(), wp.getLatitude());
               RouteData.WaypointCoordinates waypoint = new RouteData.WaypointCoordinates(location, wp.getName());
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

            List<Double> location = List.of(originalWaypoint.getLongitude(), originalWaypoint.getLatitude());
            RouteData.WaypointCoordinates waypoint = new RouteData.WaypointCoordinates(location,
                  originalWaypoint.getName());
            waypoint.setTimezone(timezone);

            Integer waypointDuration = 0;
            if (durations != null && i < durations.size()) {
               waypointDuration = durations.get(i) != null ? durations.get(i) : 0;
            }
            waypoint.setDuration(waypointDuration);

            if (i == 0) {
               waypoint.setArrivalTime(currentTimeStr);
            } else {
               RouteRequest.Waypoint previousWaypoint = originalWaypoints.get(i - 1);
               String previousTimezone = previousWaypoint.getTimezoneName();

               String arrivalTimeInCurrentTimezone = Utils.convertDateTime(currentTimeStr, previousTimezone, timezone);
               waypoint.setArrivalTime(arrivalTimeInCurrentTimezone);
               currentTimeStr = arrivalTimeInCurrentTimezone;
            }

            String departureTime = addMinutesToDateTime(currentTimeStr, timezone, waypointDuration);
            waypoint.setDepartureTime(departureTime);

            if (i < originalWaypoints.size() - 1) {
               if (i < segments.size()) {
                  RouteData.RouteSegment segment = segments.get(i);
                  if (segment.getDuration() != null) {
                     currentTimeStr = addMinutesToDateTime(departureTime, timezone,
                           (int) (segment.getDuration().longValue() / 60));
                  }
               }
            }

            waypointsWithTimes.add(waypoint);
         }

         return waypointsWithTimes;

      } catch (Exception e) {
         log.error("Error calculating arrival times with durations and timezones", e);
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

   private List<RouteData.RouteStep> parseSteps(JsonNode stepsNode) {
      List<RouteData.RouteStep> steps = new ArrayList<>();
      if (stepsNode == null || !stepsNode.isArray()) {
         return steps;
      }
      for (JsonNode stepNode : stepsNode) {
         RouteData.RouteStep step = new RouteData.RouteStep();
         if (stepNode.has("distance")) {
            step.setDistance(stepNode.get("distance").asDouble());
         }
         if (stepNode.has("duration")) {
            step.setDuration(stepNode.get("duration").asDouble());
         }
         if (stepNode.has("type")) {
            step.setType(stepNode.get("type").asInt());
         }
         if (stepNode.has("instruction")) {
            step.setInstruction(stepNode.get("instruction").asText());
         }
         if (stepNode.has("name")) {
            step.setName(stepNode.get("name").asText());
         }
         JsonNode wayPointsNode = stepNode.get("way_points");
         if (wayPointsNode != null && wayPointsNode.isArray()) {
            List<Integer> wayPoints = new ArrayList<>();
            for (JsonNode idx : wayPointsNode) {
               wayPoints.add(idx.asInt());
            }
            step.setWayPoints(wayPoints);
         }
         steps.add(step);
      }
      return steps;
   }

   private RouteData createErrorRoute(String errorMessage) {
      RouteData errorRoute = new RouteData();
      errorRoute.setGeometry(new ArrayList<>());
      errorRoute.setDistance(0.0);
      errorRoute.setDuration(0.0);
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
      private Boolean instructions;

      @JsonProperty("instructions_format")
      private String instructionsFormat;

      private String language;

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
