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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pjr22.tripweather.Utils;
import com.pjr22.tripweather.config.CacheMetricsConfig.CacheMeterNames;
import com.pjr22.tripweather.dto.LocationResolution;
import com.pjr22.tripweather.model.GeoPoint;
import com.pjr22.tripweather.model.LocationData;
import com.pjr22.tripweather.model.OrsResponseCache;
import com.pjr22.tripweather.model.RouteData;
import com.pjr22.tripweather.model.SnappedPoint;
import com.pjr22.tripweather.repository.OrsResponseCacheRepository;
import com.pjr22.tripweather.routing.PublicOrsClient;
import com.pjr22.tripweather.routing.RoutingDispatcher;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Wraps OpenRouteService with a Postgres-backed durable response cache. Each
 * of directions / snap / elevation_lookup / elevation hashes a canonical
 * request payload (with coordinates rounded to ~10 cm precision) and looks
 * the result up in {@code ors_response_cache}. On a hit within TTL, no
 * upstream call. On a miss or stale entry, calls upstream; on upstream
 * failure with a stale entry within the per-endpoint stale-max window,
 * serves stale.
 *
 * <p>Per-endpoint TTLs differ because routes can subtly change with road
 * network updates while snap and elevation are essentially geological.
 *
 * <p>"Elevation" is split across two cache namespaces because we resolve it
 * two different ways depending on where the click landed:
 * <ul>
 *   <li>{@link #CACHE_KIND_ELEVATION_LOOKUP} caches the response of a tiny
 *       self-loop directions request ({@code [(lon,lat),(lon,lat)]} with
 *       {@code elevation:true}). ORS short-circuits coincident waypoints
 *       and returns a single-point LineString with Z; this works on both
 *       local and public engines, where the open-source ORS image's
 *       {@code /elevation/point} endpoint does not exist.</li>
 *   <li>{@link #CACHE_KIND_ELEVATION} caches public {@code /elevation/point}
 *       responses for off-road clicks where snap returned no feature, so
 *       no road point exists to base directions on. Public ORS is the only
 *       way to get terrain elevation at an arbitrary point.</li>
 * </ul>
 */
@Slf4j
@Service
public class RouteService {

   private final PublicOrsClient publicOrs;
   private final RoutingDispatcher dispatcher;
   private final ObjectMapper objectMapper;
   private final OrsResponseCacheRepository cacheRepository;
   private final DbCacheMetrics cacheMetrics;
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
   private static final String CACHE_KIND_ELEVATION_LOOKUP = "elevation_lookup";

   /** Bump if the canonical request shape (or upstream response shape) changes
    *  in a way that invalidates previously-cached entries. v2 retired the
    *  {@code /elevation/point}-only elevation path in favour of the
    *  {@code elevation_lookup}/{@code elevation} two-cache split described
    *  on the class javadoc. */
   private static final int CACHE_KEY_VERSION = 2;

   private static final int SNAP_RADIUS_METERS = 10_000;
   private static final int COORDINATE_DECIMALS = 6;

   public RouteService(
         PublicOrsClient publicOrs,
         RoutingDispatcher dispatcher,
         ObjectMapper objectMapper,
         OrsResponseCacheRepository cacheRepository,
         DbCacheMetrics cacheMetrics,
         Clock clock,
         @Value("${trip.routing.directions-ttl-hours:24}") long directionsTtlHours,
         @Value("${trip.routing.directions-stale-max-hours:168}") long directionsStaleMaxHours,
         @Value("${trip.routing.snap-ttl-hours:720}") long snapTtlHours,
         @Value("${trip.routing.snap-stale-max-hours:2160}") long snapStaleMaxHours,
         @Value("${trip.routing.elevation-ttl-hours:720}") long elevationTtlHours,
         @Value("${trip.routing.elevation-stale-max-hours:2160}") long elevationStaleMaxHours
   ) {
      this.publicOrs = publicOrs;
      this.dispatcher = dispatcher;
      this.objectMapper = objectMapper;
      this.cacheRepository = cacheRepository;
      this.cacheMetrics = cacheMetrics;
      this.clock = clock;
      this.directionsTtlHours = directionsTtlHours;
      this.directionsStaleMaxHours = directionsStaleMaxHours;
      this.snapTtlHours = snapTtlHours;
      this.snapStaleMaxHours = snapStaleMaxHours;
      this.elevationTtlHours = elevationTtlHours;
      this.elevationStaleMaxHours = elevationStaleMaxHours;
   }

   public LocationData snapToLocation(double latitude, double longitude) {
      if (!publicOrs.isConfigured()) {
         return null;
      }
      try {
         String hash = snapCacheKey(latitude, longitude);
         List<double[]> coords = List.of(new double[]{longitude, latitude});
         Object body = snapBody(latitude, longitude);
         JsonNode response = getOrFetchCached(hash, CACHE_KIND_SNAP,
               snapTtlHours, snapStaleMaxHours,
               () -> dispatcher.dispatch(CACHE_KIND_SNAP, coords,
                     client -> client.post(SNAP_ENDPOINT, body)));
         if (response == null) {
            return null;
         }
         return objectMapper.treeToValue(response, LocationData.class);
      } catch (Exception e) {
         log.warn("Snap request failed for ({}, {}): {}", latitude, longitude, e.getMessage());
         return null;
      }
   }

   /**
    * Resolves an arbitrary input lat/lon to a navigation-ready point with
    * elevation. See {@link LocationResolution} for the response shape.
    *
    * <p>Path:
    * <ol>
    *   <li>Snap the input via {@link #snapToLocation(double, double)} (cached).</li>
    *   <li>If snap returns a feature, use its coordinates as both endpoints
    *       of a tiny self-loop directions request with {@code elevation:true}.
    *       ORS short-circuits coincident waypoints and returns a single-point
    *       LineString carrying the graph's stored Z. Routed through the
    *       {@link RoutingDispatcher} so local serves it when in coverage and
    *       public serves it otherwise.</li>
    *   <li>If snap returns no feature (off-road click outside the 10 km snap
    *       radius), fall back to public {@code /elevation/point} on the
    *       original input to get terrain elevation. The local engine's
    *       open-source build does not expose an elevation REST endpoint, so
    *       there is nothing local to try here.</li>
    * </ol>
    */
   public LocationResolution resolveLocation(double latitude, double longitude) {
      if (!publicOrs.isConfigured()) {
         return null;
      }

      GeoPoint original = new GeoPoint(latitude, longitude);

      LocationData snapResult = snapToLocation(latitude, longitude);
      LocationData.Feature snapFeature = firstFeature(snapResult);
      if (snapFeature != null) {
         List<Double> coords = snapFeature.getGeometry().getCoordinates();
         if (coords != null && coords.size() >= 2) {
            double snappedLon = coords.get(0);
            double snappedLat = coords.get(1);
            Double elevation = elevationFromDirections(snappedLat, snappedLon);
            return new LocationResolution(original,
                  new SnappedPoint(snappedLat, snappedLon, elevation, true));
         }
      }

      Double terrainElevation = elevationFromPublicTerrain(latitude, longitude);
      return new LocationResolution(original,
            new SnappedPoint(latitude, longitude, terrainElevation, false));
   }

   private Double elevationFromDirections(double snappedLat, double snappedLon) {
      try {
         String hash = elevationLookupCacheKey(snappedLat, snappedLon);
         List<double[]> coords = List.of(
               new double[]{snappedLon, snappedLat},
               new double[]{snappedLon, snappedLat});
         String body = objectMapper.writeValueAsString(
               elevationLookupRequest(snappedLat, snappedLon));
         JsonNode response = getOrFetchCached(hash, CACHE_KIND_ELEVATION_LOOKUP,
               elevationTtlHours, elevationStaleMaxHours,
               () -> dispatcher.dispatch(CACHE_KIND_ELEVATION_LOOKUP, coords,
                     client -> client.post(DIRECTIONS_ENDPOINT, body)));
         return extractElevationFromDirections(response);
      } catch (Exception e) {
         log.warn("Elevation lookup via directions failed for snapped ({}, {}): {}",
               snappedLat, snappedLon, e.getMessage());
         return null;
      }
   }

   private Double elevationFromPublicTerrain(double latitude, double longitude) {
      try {
         String hash = elevationCacheKey(latitude, longitude);
         String path = String.format(Locale.ROOT,
               ELEVATION_ENDPOINT + "?geometry=%.6f,%.6f", longitude, latitude);
         JsonNode response = getOrFetchCached(hash, CACHE_KIND_ELEVATION,
               elevationTtlHours, elevationStaleMaxHours,
               () -> publicOrs.get(path));
         return extractElevationFromPoint(response);
      } catch (Exception e) {
         log.warn("Public terrain elevation fallback failed for ({}, {}): {}",
               latitude, longitude, e.getMessage());
         return null;
      }
   }

   private static LocationData.Feature firstFeature(LocationData data) {
      if (data == null || data.getFeatures() == null || data.getFeatures().isEmpty()) {
         return null;
      }
      return data.getFeatures().get(0);
   }

   private static Double extractElevationFromDirections(JsonNode response) {
      if (response == null) {
         return null;
      }
      JsonNode coordArr = response
            .path("features").path(0)
            .path("geometry").path("coordinates");
      if (coordArr.isArray() && coordArr.size() > 0) {
         JsonNode first = coordArr.get(0);
         if (first.isArray() && first.size() >= 3 && !first.get(2).isNull()) {
            return first.get(2).asDouble();
         }
      }
      return null;
   }

   private static Double extractElevationFromPoint(JsonNode response) {
      if (response == null) {
         return null;
      }
      JsonNode coordsNode = response.path("geometry").path("coordinates");
      if (coordsNode.isArray() && coordsNode.size() >= 3 && !coordsNode.get(2).isNull()) {
         return coordsNode.get(2).asDouble();
      }
      return null;
   }

   public RouteData calculateRoute(
         List<RouteRequest.Waypoint> waypoints,
         ZonedDateTime departureDateTime,
         List<Integer> durations
   ) {
      if (!publicOrs.isConfigured()) {
         return createErrorRoute("OpenRouteService API key not configured");
      }
      if (waypoints == null || waypoints.size() < 2) {
         return createErrorRoute("At least 2 waypoints are required for routing");
      }

      JsonNode response;
      try {
         String hash = directionsCacheKey(waypoints);
         List<double[]> coords = waypointsToCoords(waypoints);
         String body = objectMapper.writeValueAsString(directionsRequest(waypoints));
         response = getOrFetchCached(hash, CACHE_KIND_DIRECTIONS,
               directionsTtlHours, directionsStaleMaxHours,
               () -> dispatcher.dispatch(CACHE_KIND_DIRECTIONS, coords,
                     client -> client.post(DIRECTIONS_ENDPOINT, body)));
      } catch (Exception e) {
         // The OpenRouteService error body carries the actual reason (e.g. a
         // "point not found" or "distance exceeded" code + the offending
         // coordinate). It was previously swallowed — log it, and surface a
         // human reason on the error route so the API/UI can explain the failure.
         RestClientResponseException http = findResponseException(e);
         if (http != null) {
            log.warn("Route calculation failed: ORS HTTP {} for waypoints {} — {}",
                  http.getStatusCode().value(), coordsSummary(waypoints),
                  truncate(http.getResponseBodyAsString()));
            return createErrorRoute(orsReason(http.getResponseBodyAsString(),
                  "Routing failed (HTTP " + http.getStatusCode().value() + ")"));
         }
         log.warn("Route calculation failed for waypoints {}: {}",
               coordsSummary(waypoints), e.getMessage(), e);
         return createErrorRoute("Failed to calculate route: " + e.getMessage());
      }
      if (response == null) {
         log.warn("Route calculation got no directions response for waypoints {}",
               coordsSummary(waypoints));
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
      String cacheMetricName = cacheMetricName(endpoint);

      if (cached.isPresent()) {
         OrsResponseCache entry = cached.get();
         if (now.isBefore(entry.getFetchedAt().plusHours(ttlHours))) {
            cacheMetrics.recordHit(cacheMetricName);
            return parseJsonOrNull(entry.getResponseJson());
         }
         try {
            JsonNode fresh = apiCall.execute();
            if (fresh != null) {
               cacheMetrics.recordMiss(cacheMetricName);
               persist(requestHash, endpoint, fresh, now);
               return fresh;
            }
         } catch (Exception e) {
            if (now.isBefore(entry.getFetchedAt().plusHours(staleMaxHours))) {
               // Stale-on-error counts as a hit: the cache served the
               // response. Operators read elevated miss-rates as upstream
               // pressure; recording stale-served as a miss would falsely
               // imply we paid the upstream cost.
               cacheMetrics.recordHit(cacheMetricName);
               log.warn("ORS {} refresh failed; serving stale cached entry from {}",
                     endpoint, entry.getFetchedAt(), e);
               return parseJsonOrNull(entry.getResponseJson());
            }
            cacheMetrics.recordMiss(cacheMetricName);
            throw e;
         }
         // apiCall returned null without throwing — fall back to stale within window
         if (now.isBefore(entry.getFetchedAt().plusHours(staleMaxHours))) {
            cacheMetrics.recordHit(cacheMetricName);
            log.warn("ORS {} returned null; serving stale cached entry from {}",
                  endpoint, entry.getFetchedAt());
            return parseJsonOrNull(entry.getResponseJson());
         }
         cacheMetrics.recordMiss(cacheMetricName);
         return null;
      }

      cacheMetrics.recordMiss(cacheMetricName);
      JsonNode fresh = apiCall.execute();
      if (fresh != null) {
         persist(requestHash, endpoint, fresh, now);
      }
      return fresh;
   }

   /** Map the internal {@code endpoint} string used as a {@code ors_response_cache.endpoint}
    *  value to the {@code cache} tag value on {@code cache.gets} meters. Keeps a single
    *  source of truth in {@link CacheMeterNames}; the four endpoint values are stable
    *  ({@code directions}, {@code snap}, {@code elevation}, {@code elevation_lookup}). */
   private static String cacheMetricName(String endpoint) {
      return switch (endpoint) {
         case CACHE_KIND_DIRECTIONS         -> CacheMeterNames.ORS_DIRECTIONS;
         case CACHE_KIND_SNAP               -> CacheMeterNames.ORS_SNAP;
         case CACHE_KIND_ELEVATION          -> CacheMeterNames.ORS_ELEVATION;
         case CACHE_KIND_ELEVATION_LOOKUP   -> CacheMeterNames.ORS_ELEVATION_LOOKUP;
         default -> "ors-" + endpoint;
      };
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

   private String elevationLookupCacheKey(double snappedLat, double snappedLon) {
      // Keyed by the snapped point so two distinct clicks that snap to the
      // same road node share a cache entry.
      return pointCacheKey(CACHE_KIND_ELEVATION_LOOKUP, snappedLat, snappedLon);
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

   // ------------------------------------------------------ request body shape

   private RouteRequest directionsRequest(List<RouteRequest.Waypoint> waypoints) {
      RouteRequest request = new RouteRequest();
      request.setCoordinates(convertWaypointsToCoordinates(waypoints));
      request.setRadiuses(List.of(-1));
      request.setElevation(true);
      request.setInstructions(true);
      request.setInstructionsFormat("text");
      request.setLanguage("en");
      return request;
   }

   private static Map<String, Object> snapBody(double latitude, double longitude) {
      Map<String, Object> body = new HashMap<>();
      body.put("locations", List.of(List.of(longitude, latitude)));
      body.put("radius", Integer.valueOf(SNAP_RADIUS_METERS));
      return body;
   }

   private static Map<String, Object> elevationLookupRequest(double lat, double lon) {
      // Coincident waypoints so ORS short-circuits routing (distance=0,
      // single-point LineString geometry) and we just get the Z back.
      // instructions=false trims the response payload.
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("coordinates", List.of(
            List.of(lon, lat),
            List.of(lon, lat)));
      body.put("elevation", Boolean.TRUE);
      body.put("instructions", Boolean.FALSE);
      return body;
   }

   private static List<double[]> waypointsToCoords(List<RouteRequest.Waypoint> waypoints) {
      List<double[]> result = new ArrayList<>(waypoints.size());
      for (RouteRequest.Waypoint wp : waypoints) {
         result.add(new double[]{wp.getLongitude(), wp.getLatitude()});
      }
      return result;
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
      errorRoute.setError(errorMessage);
      return errorRoute;
   }

   /** First {@link RestClientResponseException} in the cause chain, or null. */
   private static RestClientResponseException findResponseException(Throwable t) {
      while (t != null) {
         if (t instanceof RestClientResponseException rcre) {
            return rcre;
         }
         t = t.getCause();
      }
      return null;
   }

   /** Pull OpenRouteService's human message ({@code error.message}) from its
    *  error body, falling back to the supplied default. */
   private String orsReason(String body, String fallback) {
      if (body == null || body.isBlank()) {
         return fallback;
      }
      try {
         JsonNode message = objectMapper.readTree(body).path("error").path("message");
         if (message.isTextual() && !message.asText().isBlank()) {
            return message.asText();
         }
      } catch (Exception ignore) {
         // Not JSON / unexpected shape — use the fallback.
      }
      return fallback;
   }

   /** Compact "[(lon,lat), …]" summary of the request coordinates for logs. */
   private static String coordsSummary(List<RouteRequest.Waypoint> waypoints) {
      StringBuilder sb = new StringBuilder("[");
      for (int i = 0; i < waypoints.size(); i++) {
         RouteRequest.Waypoint w = waypoints.get(i);
         if (i > 0) {
            sb.append(", ");
         }
         sb.append(String.format(Locale.ROOT, "(%.5f,%.5f)", w.getLongitude(), w.getLatitude()));
      }
      return sb.append(']').toString();
   }

   private static String truncate(String s) {
      if (s == null) {
         return "(none)";
      }
      String t = s.strip();
      return t.length() <= 1000 ? t : t.substring(0, 1000) + "…(truncated)";
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
