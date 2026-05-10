package com.pjr22.tripweather.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.pjr22.tripweather.dto.LocationResolution;
import com.pjr22.tripweather.model.GeocodeReverseCache;
import com.pjr22.tripweather.model.LocationData;
import com.pjr22.tripweather.repository.GeocodeReverseCacheRepository;

import lombok.extern.slf4j.Slf4j;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

/**
 * Wraps Geoapify with two cache layers:
 *
 *   1. PostGIS-backed durable cache of /geocode/reverse responses, looked up
 *      via ST_DWithin within a small radius. Insert-only — refresh-on-stale
 *      simply inserts a newer row that supersedes the old via the timestamp
 *      tiebreak.
 *
 *   2. In-memory Caffeine cache of /geocode/search responses keyed by
 *      lowercased + trimmed query string. Plain TTL eviction; forward
 *      geocoding is low volume and results are non-critical.
 */
@Service
@Slf4j
public class LocationService {

    private static final int SRID_WGS84 = 4326;
    private static final GeometryFactory GEOMETRY_FACTORY =
            new GeometryFactory(new PrecisionModel(), SRID_WGS84);

    private final RouteService routeService;
    private final RestClient restClient;
    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final GeocodeReverseCacheRepository reverseCacheRepository;
    private final Cache<String, JsonNode> forwardCache;
    private final Clock clock;
    private final double reverseRadiusMeters;
    private final long reverseRefreshDays;

    public LocationService(
            @Value("${geoapify.api.key}") String apiKey,
            RestClient geoapifyRestClient,
            RouteService routeService,
            ObjectMapper objectMapper,
            GeocodeReverseCacheRepository reverseCacheRepository,
            Cache<String, JsonNode> forwardGeocodeCache,
            Clock clock,
            @Value("${trip.geocode.reverse-radius-meters:15}") double reverseRadiusMeters,
            @Value("${trip.geocode.reverse-refresh-days:365}") long reverseRefreshDays
    ) {
        this.apiKey = apiKey;
        this.restClient = geoapifyRestClient;
        this.routeService = routeService;
        this.objectMapper = objectMapper;
        this.reverseCacheRepository = reverseCacheRepository;
        this.forwardCache = forwardGeocodeCache;
        this.clock = clock;
        this.reverseRadiusMeters = reverseRadiusMeters;
        this.reverseRefreshDays = reverseRefreshDays;
    }

    public LocationData reverseGeocode(double latitude, double longitude) {
        requireApiKey();

        Optional<GeocodeReverseCache> cached =
                reverseCacheRepository.findNearest(longitude, latitude, reverseRadiusMeters);
        LocationData stale = null;
        if (cached.isPresent()) {
            GeocodeReverseCache entry = cached.get();
            LocalDateTime refreshAfter = entry.getFetchedAt().plusDays(reverseRefreshDays);
            if (LocalDateTime.now(clock).isBefore(refreshAfter)) {
                LocationData fresh = deserializeOrNull(entry.getResponseJson());
                if (fresh != null) {
                    return mergeResolution(fresh, latitude, longitude);
                }
            }
            stale = deserializeOrNull(entry.getResponseJson());
        }

        try {
            String responseBody = restClient.get()
                    .uri(String.format(Locale.ROOT,
                            "/geocode/reverse?lat=%.6f&lon=%.6f&apiKey=%s",
                            latitude, longitude, apiKey))
                    .retrieve()
                    .body(String.class);
            if (responseBody != null) {
                LocationData parsed = objectMapper.readValue(responseBody, LocationData.class);
                GeocodeReverseCache entry = new GeocodeReverseCache(null,
                        pointFromLatLon(latitude, longitude), responseBody, LocalDateTime.now(clock));
                try {
                    reverseCacheRepository.save(entry);
                } catch (Exception e) {
                    log.warn("Failed to persist reverse-geocode cache entry for ({}, {})",
                            latitude, longitude, e);
                }
                return mergeResolution(parsed, latitude, longitude);
            }
        } catch (Exception e) {
            if (stale != null) {
                log.warn("Reverse-geocode refresh failed for ({}, {}); serving stale cached entry",
                        latitude, longitude, e);
                return mergeResolution(stale, latitude, longitude);
            }
            // Preserve prior behavior: any failure on a true cache miss surfaces
            // to the caller. The controller wraps this in a 500 response.
            throw new RuntimeException("Reverse-geocode failed for ("
                    + latitude + ", " + longitude + ")", e);
        }
        return null;
    }

    public JsonNode searchLocations(String searchText) {
        requireApiKey();
        if (searchText == null || searchText.isBlank()) {
            return null;
        }
        String key = searchText.trim().toLowerCase(Locale.ROOT);
        JsonNode cached = forwardCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        JsonNode response = restClient.get()
                .uri(String.format("/geocode/search?apiKey=%s&text=%s", apiKey, searchText))
                .retrieve()
                .body(JsonNode.class);
        if (response != null) {
            forwardCache.put(key, response);
        }
        return response;
    }

    private LocationData mergeResolution(LocationData data, double latitude, double longitude) {
        if (data == null) {
            return null;
        }
        LocationResolution resolution = routeService.resolveLocation(latitude, longitude);
        if (resolution != null) {
            data.setOriginal(resolution.getOriginal());
            data.setSnapped(resolution.getSnapped());
        }
        return data;
    }

    private LocationData deserializeOrNull(String json) {
        try {
            return objectMapper.readValue(json, LocationData.class);
        } catch (Exception e) {
            log.warn("Could not deserialize cached reverse-geocode response", e);
            return null;
        }
    }

    private static Point pointFromLatLon(double latitude, double longitude) {
        Point p = GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude));
        p.setSRID(SRID_WGS84);
        return p;
    }

    private void requireApiKey() {
        if (apiKey == null || apiKey.isBlank()
                || apiKey.startsWith("set with ")) {
            throw new IllegalStateException(
                    "GEOAPIFY_API_KEY environment variable is not set. "
                  + "Location services are unavailable until it is configured.");
        }
    }

    /**
     * Generates a location name from address components.
     * Combines addressLine1, addressLine2, city, and state_code to create a formatted location name.
     *
     * @param properties LocationData.Properties object containing address information
     * @return Generated location name (e.g., "99 West 12th Avenue, Denver, CO")
     *         or null if properties is invalid
     */
    public String generateLocationName(LocationData.Properties properties) {
        if (properties == null) {
            return null;
        }

        StringBuilder locationName = new StringBuilder();

        if (properties.getAddressLine1() != null && !properties.getAddressLine1().trim().isEmpty()) {
            locationName.append(properties.getAddressLine1().trim());
        }

        if (properties.getCity() != null && !properties.getCity().trim().isEmpty()) {
            if (locationName.length() > 0) {
                locationName.append(", ");
            }
            locationName.append(properties.getCity().trim());
        }

        if (properties.getStateCode() != null && !properties.getStateCode().trim().isEmpty()) {
            if (locationName.length() > 0) {
                locationName.append(", ");
            }
            locationName.append(properties.getStateCode().trim());
        }

        if (locationName.length() == 0 && properties.getAddressLine2() != null && !properties.getAddressLine2().trim().isEmpty()) {
            locationName.append(properties.getAddressLine2().trim());
        }

        if (locationName.length() == 0 && properties.getFormatted() != null && !properties.getFormatted().trim().isEmpty()) {
            locationName.append(properties.getFormatted().trim());
        }

        return locationName.length() > 0 ? locationName.toString() : null;
    }
}
