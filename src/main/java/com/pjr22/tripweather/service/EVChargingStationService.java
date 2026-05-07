package com.pjr22.tripweather.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pjr22.tripweather.dto.EVChargingStationRequest;
import com.pjr22.tripweather.dto.EVChargingStationResponse;
import com.pjr22.tripweather.dto.EVChargingStationResponse.EVChargingStationFeature;
import com.pjr22.tripweather.dto.EVChargingStationResponse.EVChargingStationGeometry;
import com.pjr22.tripweather.dto.EVChargingStationResponse.EVChargingStationProperties;
import com.pjr22.tripweather.repository.EvStationQueryDao;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Returns EV charging stations along a route by querying the local
 * {@code ev_stations} mirror — no upstream NREL call on the request path.
 * The mirror is refreshed weekly by {@link EvStationLoader}; the
 * {@code NREL_API_KEY} is now used only by the loader.
 *
 * <p>The request and response shapes are unchanged from when this service
 * proxied NREL directly: the frontend sends NREL-style filter parameters
 * ({@code fuel_type}, {@code status}, {@code access}, {@code ev_network},
 * {@code ev_connector_type}, {@code ev_charging_level}, {@code distance},
 * {@code limit}) and receives a GeoJSON {@code FeatureCollection} with the
 * same {@code properties} payload NREL would have returned. Filters apply the
 * same semantics here that NREL applies to its single-station endpoint, so
 * switching to the mirror is transparent to the UI.
 */
@Service
@Slf4j
public class EVChargingStationService {

    private static final double METERS_PER_MILE = 1609.344;

    private final EvStationQueryDao queryDao;
    private final ObjectMapper objectMapper;
    private final double defaultRadiusMiles;
    private final int defaultLimit;

    public EVChargingStationService(EvStationQueryDao queryDao,
                                    ObjectMapper objectMapper,
                                    @Value("${trip.ev.default-radius-miles:1.0}") double defaultRadiusMiles,
                                    @Value("${trip.ev.default-limit:200}") int defaultLimit) {
        this.queryDao = queryDao;
        this.objectMapper = objectMapper;
        this.defaultRadiusMiles = defaultRadiusMiles;
        this.defaultLimit = defaultLimit;
    }

    public EVChargingStationResponse getStationsAlongRoute(EVChargingStationRequest request) {
        EVChargingStationResponse response = new EVChargingStationResponse();
        response.setType("FeatureCollection");
        response.setFeatures(new ArrayList<>());

        if (request == null || request.getRoute() == null || request.getRoute().isEmpty()) {
            return response;
        }

        Map<String, Object> params = request.getParameters() != null
                ? request.getParameters() : Map.of();

        String routeWkt;
        try {
            routeWkt = convertRouteToWkt(request.getRoute());
        } catch (IllegalArgumentException e) {
            log.warn("Rejected EV station request with invalid route geometry: {}", e.getMessage());
            return response;
        }

        double radiusMeters = readMiles(params, "distance", defaultRadiusMiles) * METERS_PER_MILE;
        int limit = readInt(params, "limit", defaultLimit);

        EvStationQueryDao.Filter filter = buildFilter(params);

        List<EvStationQueryDao.StationRow> rows;
        try {
            rows = queryDao.findAlongRoute(routeWkt, radiusMeters, filter, limit);
        } catch (Exception e) {
            log.error("EV station query failed", e);
            return response;
        }

        List<EVChargingStationFeature> features = new ArrayList<>(rows.size());
        for (EvStationQueryDao.StationRow row : rows) {
            EVChargingStationFeature feature = toFeature(row);
            if (feature != null) {
                features.add(feature);
            }
        }
        response.setFeatures(features);
        log.debug("EV station query returned {} feature(s) within {} m of route", features.size(), radiusMeters);
        return response;
    }

    private EvStationQueryDao.Filter buildFilter(Map<String, Object> params) {
        String fuelType = readString(params, "fuel_type");
        String status   = readString(params, "status");
        String access   = readString(params, "access");
        List<String> networks       = readCommaSeparated(params, "ev_network");
        List<String> connectorTypes = readCommaSeparated(params, "ev_connector_type");
        List<String> chargingLevels = readCommaSeparated(params, "ev_charging_level");

        boolean requireDcFast = chargingLevels.stream().anyMatch(s -> s.equalsIgnoreCase("dc_fast"));
        boolean requireLevel2 = chargingLevels.stream().anyMatch(s -> s.equals("2") || s.equalsIgnoreCase("level2"));
        boolean requireLevel1 = chargingLevels.stream().anyMatch(s -> s.equals("1") || s.equalsIgnoreCase("level1"));

        return new EvStationQueryDao.Filter(
                fuelType, status, access,
                networks.isEmpty() ? null : networks,
                connectorTypes.isEmpty() ? null : connectorTypes,
                requireDcFast, requireLevel2, requireLevel1);
    }

    private EVChargingStationFeature toFeature(EvStationQueryDao.StationRow row) {
        EVChargingStationFeature feature = new EVChargingStationFeature();
        feature.setType("Feature");

        EVChargingStationGeometry geometry = new EVChargingStationGeometry();
        geometry.setType("Point");
        geometry.setCoordinates(List.of(row.longitude(), row.latitude()));
        feature.setGeometry(geometry);

        try {
            EVChargingStationProperties properties = objectMapper.readValue(
                    row.propertiesJson(), EVChargingStationProperties.class);
            feature.setProperties(properties);
        } catch (Exception e) {
            log.warn("Could not deserialize cached properties for station id {}; skipping", row.id(), e);
            return null;
        }
        return feature;
    }

    private static String readString(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static List<String> readCommaSeparated(Map<String, Object> params, String key) {
        String raw = readString(params, key);
        if (raw == null) return List.of();
        List<String> parts = new ArrayList<>();
        for (String p : raw.split(",")) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    private static double readMiles(Map<String, Object> params, String key, double fallback) {
        Object v = params.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignore) {}
        }
        return fallback;
    }

    private static int readInt(Map<String, Object> params, String key, int fallback) {
        Object v = params.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignore) {}
        }
        return fallback;
    }

    private String convertRouteToWkt(List<List<Double>> route) {
        if (route == null || route.isEmpty()) {
            throw new IllegalArgumentException("Route cannot be null or empty");
        }
        StringBuilder wkt = new StringBuilder("LINESTRING(");
        for (int i = 0; i < route.size(); i++) {
            List<Double> point = route.get(i);
            if (point == null || point.size() < 2 || point.get(0) == null || point.get(1) == null) {
                throw new IllegalArgumentException("Invalid route point at index " + i);
            }
            wkt.append(String.format(Locale.ROOT, "%f %f", point.get(0), point.get(1)));
            if (i < route.size() - 1) {
                wkt.append(", ");
            }
        }
        wkt.append(')');
        return wkt.toString();
    }
}
