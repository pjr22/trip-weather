package com.pjr22.tripweather.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.pjr22.tripweather.model.NwsGridpoint;
import com.pjr22.tripweather.model.WeatherData;
import com.pjr22.tripweather.repository.NwsGridpointRepository;

import lombok.extern.slf4j.Slf4j;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Wraps api.weather.gov with two cache layers:
 *
 *   1. PostGIS-backed durable cache of /points/{lat,lon} responses
 *      (lat/lon -> gridpoint URL, with the gridpoint polygon stored so
 *      lookups use ST_Covers and get the correct cell, not a quantized
 *      neighbor). Refreshed lazily after gridpoint-refresh-days.
 *
 *   2. In-memory Caffeine cache of forecast JSON keyed by gridpoint URL.
 *      Freshness anchored to the response's updateTime when present.
 *      Stale-on-error: if upstream is unreachable past freshness, the cached
 *      entry is served until stale-max-hours, then we fail.
 */
@Service
@Slf4j
public class WeatherService {

    private static final int SRID_WGS84 = 4326;
    private static final GeometryFactory GEOMETRY_FACTORY =
            new GeometryFactory(new PrecisionModel(), SRID_WGS84);

    private final RestClient restClient;
    private final NwsGridpointRepository gridpointRepository;
    private final Cache<String, CachedForecast> forecastCache;
    private final Clock clock;
    private final long forecastTtlMinutes;
    private final long forecastStaleMaxHours;
    private final long gridpointRefreshDays;

    public WeatherService(RestClient nwsRestClient,
                          NwsGridpointRepository gridpointRepository,
                          Cache<String, CachedForecast> forecastCache,
                          Clock clock,
                          @Value("${trip.weather.forecast-ttl-minutes:30}") long forecastTtlMinutes,
                          @Value("${trip.weather.forecast-stale-max-hours:6}") long forecastStaleMaxHours,
                          @Value("${trip.weather.gridpoint-refresh-days:90}") long gridpointRefreshDays) {
        this.restClient = nwsRestClient;
        this.gridpointRepository = gridpointRepository;
        this.forecastCache = forecastCache;
        this.clock = clock;
        this.forecastTtlMinutes = forecastTtlMinutes;
        this.forecastStaleMaxHours = forecastStaleMaxHours;
        this.gridpointRefreshDays = gridpointRefreshDays;
    }

    public WeatherData getWeatherForecast(double latitude, double longitude, String date, String time) {
        try {
            String forecastUrl = resolveForecastUrl(latitude, longitude);
            if (forecastUrl == null) {
                return WeatherData.createError("Unable to get forecast URL for location");
            }

            JsonNode forecastData = fetchForecastWithCache(forecastUrl);
            if (forecastData == null || !forecastData.has("properties")) {
                return WeatherData.createError("Invalid forecast data");
            }

            LocalDateTime targetDateTime = parseDateTime(date, time);
            JsonNode periods = forecastData.get("properties").get("periods");
            JsonNode matchingPeriod = findMatchingPeriod(periods, targetDateTime);
            if (matchingPeriod == null) {
                return WeatherData.createError("No forecast available for selected date/time");
            }
            return extractWeatherData(matchingPeriod);

        } catch (Exception e) {
            log.error("Failed to fetch weather forecast for ({}, {}) at {} {}",
                    latitude, longitude, date, time, e);
            return WeatherData.createError("Error fetching weather: " + e.getMessage());
        }
    }

    private String resolveForecastUrl(double latitude, double longitude) {
        Optional<NwsGridpoint> cached = gridpointRepository.findContainingPoint(longitude, latitude);
        if (cached.isPresent()) {
            NwsGridpoint gp = cached.get();
            LocalDateTime refreshAfter = gp.getFetchedAt().plusDays(gridpointRefreshDays);
            if (LocalDateTime.now(clock).isBefore(refreshAfter)) {
                return preferredUrl(gp.getHourlyUrl(), gp.getForecastUrl());
            }
            String refreshed = fetchAndStoreGridpoint(latitude, longitude);
            if (refreshed != null) {
                return refreshed;
            }
            log.warn("Refresh of stale gridpoint failed for ({}, {}); serving stale cached entry",
                    latitude, longitude);
            return preferredUrl(gp.getHourlyUrl(), gp.getForecastUrl());
        }
        return fetchAndStoreGridpoint(latitude, longitude);
    }

    private String fetchAndStoreGridpoint(double latitude, double longitude) {
        JsonNode pointsData;
        try {
            pointsData = restClient.get()
                    .uri(String.format("/points/%.4f,%.4f", latitude, longitude))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.error("Failed to look up forecast URL for ({}, {})", latitude, longitude, e);
            return null;
        }
        if (pointsData == null || !pointsData.has("properties")) {
            return null;
        }
        JsonNode props = pointsData.get("properties");
        String forecastUrl = textOrNull(props, "forecast");
        String hourlyUrl   = textOrNull(props, "forecastHourly");
        if (forecastUrl == null && hourlyUrl == null) {
            return null;
        }

        String office = textOrNull(props, "gridId");
        Integer gridX = props.has("gridX") ? props.get("gridX").asInt() : null;
        Integer gridY = props.has("gridY") ? props.get("gridY").asInt() : null;
        Polygon geom  = parseGeoJsonPolygonOrNull(pointsData.get("geometry"));

        if (geom != null && office != null && gridX != null && gridY != null) {
            NwsGridpoint gp = new NwsGridpoint(office, gridX, gridY, geom,
                    forecastUrl != null ? forecastUrl : "",
                    hourlyUrl   != null ? hourlyUrl   : "",
                    LocalDateTime.now(clock));
            try {
                gridpointRepository.save(gp);
            } catch (Exception e) {
                log.warn("Failed to persist gridpoint cache entry for ({}, {})",
                        latitude, longitude, e);
            }
        } else {
            // Per Phase 1 Q6: skip caching rather than cache something
            // potentially wrong. The request still succeeds via the URL we
            // pulled from the response — only future calls won't hit cache.
            log.warn("Skipping gridpoint cache insert for ({}, {}): missing geom or grid identifiers",
                    latitude, longitude);
        }
        return preferredUrl(hourlyUrl, forecastUrl);
    }

    private JsonNode fetchForecastWithCache(String forecastUrl) {
        Instant now = clock.instant();
        CachedForecast cached = forecastCache.getIfPresent(forecastUrl);
        if (cached != null && now.isBefore(cached.freshUntil())) {
            return cached.data();
        }
        try {
            JsonNode fresh = restClient.get()
                    .uri(forecastUrl)
                    .retrieve()
                    .body(JsonNode.class);
            if (fresh != null) {
                Instant freshUntil = computeFreshUntil(fresh, now);
                forecastCache.put(forecastUrl, new CachedForecast(fresh, freshUntil, now));
                return fresh;
            }
        } catch (Exception e) {
            if (cached != null) {
                Instant staleCutoff = cached.fetchedAt().plus(Duration.ofHours(forecastStaleMaxHours));
                if (now.isBefore(staleCutoff)) {
                    log.warn("Forecast refresh failed for {}; serving stale entry from {}",
                            forecastUrl, cached.fetchedAt(), e);
                    return cached.data();
                }
            }
            log.error("Forecast fetch failed and no usable cached entry for {}", forecastUrl, e);
            return null;
        }
        return null;
    }

    private Instant computeFreshUntil(JsonNode forecast, Instant fetchedAt) {
        JsonNode props = forecast.get("properties");
        if (props != null && props.has("updateTime")) {
            try {
                // NWS publishes new forecasts roughly hourly; one hour past
                // updateTime is a safe "guaranteed fresh" horizon.
                return Instant.parse(props.get("updateTime").asText()).plus(Duration.ofHours(1));
            } catch (Exception e) {
                log.debug("Could not parse forecast updateTime; falling back to TTL", e);
            }
        }
        return fetchedAt.plus(Duration.ofMinutes(forecastTtlMinutes));
    }

    private static String preferredUrl(String hourlyUrl, String forecastUrl) {
        if (hourlyUrl != null && !hourlyUrl.isBlank()) return hourlyUrl;
        if (forecastUrl != null && !forecastUrl.isBlank()) return forecastUrl;
        return null;
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    private Polygon parseGeoJsonPolygonOrNull(JsonNode geometry) {
        if (geometry == null) return null;
        try {
            if (!"Polygon".equals(geometry.path("type").asText())) return null;
            JsonNode rings = geometry.get("coordinates");
            if (rings == null || !rings.isArray() || rings.isEmpty()) return null;
            JsonNode outerRing = rings.get(0);
            Coordinate[] coords = new Coordinate[outerRing.size()];
            for (int i = 0; i < outerRing.size(); i++) {
                JsonNode pt = outerRing.get(i);
                coords[i] = new Coordinate(pt.get(0).asDouble(), pt.get(1).asDouble());
            }
            Polygon polygon = GEOMETRY_FACTORY.createPolygon(coords);
            polygon.setSRID(SRID_WGS84);
            return polygon;
        } catch (Exception e) {
            log.warn("Could not parse GeoJSON polygon", e);
            return null;
        }
    }

    private LocalDateTime parseDateTime(String date, String time) {
        return LocalDateTime.parse(date + "T" + time);
    }

    private JsonNode findMatchingPeriod(JsonNode periods, LocalDateTime targetDateTime) {
        if (periods == null || !periods.isArray()) {
            return null;
        }
        for (JsonNode period : periods) {
            if (period.has("startTime") && period.has("endTime")) {
                ZonedDateTime startTime = ZonedDateTime.parse(period.get("startTime").asText());
                ZonedDateTime endTime   = ZonedDateTime.parse(period.get("endTime").asText());
                ZonedDateTime targetZoned = targetDateTime.atZone(startTime.getZone());
                if (!targetZoned.isBefore(startTime) && targetZoned.isBefore(endTime)) {
                    return period;
                }
            }
        }
        if (periods.size() > 0) {
            return periods.get(0);
        }
        return null;
    }

    private WeatherData extractWeatherData(JsonNode period) {
        String condition = period.has("shortForecast") ?
                period.get("shortForecast").asText() : "Unknown";

        Integer temperature = period.has("temperature") ?
                period.get("temperature").asInt() : null;

        String temperatureUnit = period.has("temperatureUnit") ?
                period.get("temperatureUnit").asText() : "F";

        String windSpeed = period.has("windSpeed") ?
                period.get("windSpeed").asText() : "Unknown";

        String windDirection = period.has("windDirection") ?
                period.get("windDirection").asText() : "Unknown";

        String iconUrl = period.has("icon") ?
                period.get("icon").asText() : null;

        Integer precipitationProbability = null;
        if (period.has("probabilityOfPrecipitation")) {
            JsonNode precipNode = period.get("probabilityOfPrecipitation");
            if (precipNode.has("value") && !precipNode.get("value").isNull()) {
                precipitationProbability = precipNode.get("value").asInt();
            }
        }

        return new WeatherData(condition, temperature, temperatureUnit, windSpeed,
                windDirection, iconUrl, precipitationProbability);
    }
}
