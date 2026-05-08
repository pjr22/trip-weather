package com.pjr22.tripweather.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pjr22.tripweather.model.NwsGridpoint;
import com.pjr22.tripweather.model.WeatherData;
import com.pjr22.tripweather.repository.NwsGridpointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class WeatherServiceTest {

    private static final double LAT = 40.0;
    private static final double LON = -105.25;
    private static final String DATE = "2030-01-01";
    private static final String TIME = "13:30";
    private static final String POINTS_URL =
            "https://api.weather.gov/points/40.0000,-105.2500";
    private static final String FORECAST_URL =
            "https://api.weather.gov/gridpoints/BOU/62,61/forecast";
    private static final String HOURLY_URL =
            "https://api.weather.gov/gridpoints/BOU/62,61/forecast/hourly";

    // Real /points/ responses return geometry as a Point (the requested
    // coordinate), not the grid-cell polygon — see api.weather.gov docs.
    // The polygon lives on the /gridpoints/.../forecast response below.
    private static final String POINTS_RESPONSE = """
            {
              "properties": {
                "gridId": "BOU",
                "gridX": 62,
                "gridY": 61,
                "forecast": "%s",
                "forecastHourly": "%s"
              },
              "geometry": {
                "type": "Point",
                "coordinates": [-105.25, 40.0]
              }
            }
            """.formatted(FORECAST_URL, HOURLY_URL);

    private static final String FORECAST_RESPONSE = """
            {
              "properties": {
                "updateTime": "2030-01-01T12:00:00+00:00",
                "periods": [{
                  "startTime": "2030-01-01T13:00:00-07:00",
                  "endTime":   "2030-01-01T14:00:00-07:00",
                  "shortForecast": "Sunny",
                  "temperature": 60,
                  "temperatureUnit": "F",
                  "windSpeed": "5 mph",
                  "windDirection": "NW",
                  "icon": "https://api.weather.gov/icons/land/day/skc?size=medium",
                  "probabilityOfPrecipitation": { "value": 0 }
                }]
              },
              "geometry": {
                "type": "Polygon",
                "coordinates": [[
                  [-105.27, 39.99],
                  [-105.24, 39.99],
                  [-105.24, 40.02],
                  [-105.27, 40.02],
                  [-105.27, 39.99]
                ]]
              }
            }
            """;

    private static final String FORECAST_RESPONSE_NO_POLYGON = """
            {
              "properties": {
                "updateTime": "2030-01-01T12:00:00+00:00",
                "periods": [{
                  "startTime": "2030-01-01T13:00:00-07:00",
                  "endTime":   "2030-01-01T14:00:00-07:00",
                  "shortForecast": "Sunny",
                  "temperature": 60,
                  "temperatureUnit": "F",
                  "windSpeed": "5 mph",
                  "windDirection": "NW",
                  "icon": "https://api.weather.gov/icons/land/day/skc?size=medium",
                  "probabilityOfPrecipitation": { "value": 0 }
                }]
              }
            }
            """;

    @Mock private NwsGridpointRepository gridpointRepository;

    private RestClient restClient;
    private MockRestServiceServer mockServer;
    private Cache<String, CachedForecast> forecastCache;
    private Clock fixedClock;
    private WeatherService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.weather.gov");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();

        forecastCache = Caffeine.newBuilder().maximumSize(100).build();
        // Pinned well after the forecast updateTime so cache freshness windows are deterministic.
        fixedClock = Clock.fixed(Instant.parse("2030-01-01T20:00:00Z"), ZoneOffset.UTC);

        service = new WeatherService(restClient, gridpointRepository, forecastCache,
                fixedClock,
                new com.pjr22.tripweather.config.TileProxyConfig(false, ""),
                30L, 6L, 90L);
    }

    @Test
    void gridpointCacheHit_skipsPointsApi() {
        when(gridpointRepository.findContainingPoint(LON, LAT))
                .thenReturn(Optional.of(freshGridpoint()));
        mockServer.expect(requestTo(FORECAST_URL.replace("/forecast", "/forecast/hourly")))
                .andRespond(withSuccess(FORECAST_RESPONSE, MediaType.APPLICATION_JSON));

        WeatherData result = service.getWeatherForecast(LAT, LON, DATE, TIME);

        assertThat(result.getCondition()).isEqualTo("Sunny");
        verifyNoUpsert();
        mockServer.verify();
    }

    @Test
    void gridpointCacheMiss_callsPointsApiAndStoresEntity() {
        when(gridpointRepository.findContainingPoint(LON, LAT))
                .thenReturn(Optional.empty());
        mockServer.expect(requestTo(POINTS_URL))
                .andRespond(withSuccess(POINTS_RESPONSE, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(HOURLY_URL))
                .andRespond(withSuccess(FORECAST_RESPONSE, MediaType.APPLICATION_JSON));

        WeatherData result = service.getWeatherForecast(LAT, LON, DATE, TIME);

        assertThat(result.getCondition()).isEqualTo("Sunny");
        verify(gridpointRepository).upsert(
                eq("BOU"), eq(62), eq(61),
                argThat(wkt -> wkt != null && wkt.startsWith("POLYGON")),
                eq(FORECAST_URL), eq(HOURLY_URL),
                any(LocalDateTime.class));
        mockServer.verify();
    }

    @Test
    void forecastResponseMissingPolygon_returnsForecastButDoesNotPersist() {
        when(gridpointRepository.findContainingPoint(LON, LAT))
                .thenReturn(Optional.empty());
        mockServer.expect(requestTo(POINTS_URL))
                .andRespond(withSuccess(POINTS_RESPONSE, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(HOURLY_URL))
                .andRespond(withSuccess(FORECAST_RESPONSE_NO_POLYGON, MediaType.APPLICATION_JSON));

        WeatherData result = service.getWeatherForecast(LAT, LON, DATE, TIME);

        assertThat(result.getCondition()).isEqualTo("Sunny");
        verifyNoUpsert();
        mockServer.verify();
    }

    @Test
    void pointsResponseMissingGridIdentifiers_returnsForecastButDoesNotPersist() {
        String pointsResponseNoGrid = """
                {
                  "properties": {
                    "forecast": "%s",
                    "forecastHourly": "%s"
                  },
                  "geometry": {
                    "type": "Point",
                    "coordinates": [-105.25, 40.0]
                  }
                }
                """.formatted(FORECAST_URL, HOURLY_URL);
        when(gridpointRepository.findContainingPoint(LON, LAT))
                .thenReturn(Optional.empty());
        mockServer.expect(requestTo(POINTS_URL))
                .andRespond(withSuccess(pointsResponseNoGrid, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(HOURLY_URL))
                .andRespond(withSuccess(FORECAST_RESPONSE, MediaType.APPLICATION_JSON));

        WeatherData result = service.getWeatherForecast(LAT, LON, DATE, TIME);

        assertThat(result.getCondition()).isEqualTo("Sunny");
        verifyNoUpsert();
        mockServer.verify();
    }

    private void verifyNoUpsert() {
        verify(gridpointRepository, never()).upsert(
                anyString(), anyInt(), anyInt(),
                anyString(), anyString(), anyString(),
                any(LocalDateTime.class));
    }

    @Test
    void forecastCacheHit_skipsForecastApi() {
        when(gridpointRepository.findContainingPoint(LON, LAT))
                .thenReturn(Optional.of(freshGridpoint()));

        // Pre-populate cache so any HTTP call would be unexpected — MockRestServiceServer
        // fails the test on unexpected requests by default.
        Instant now = fixedClock.instant();
        JsonNode forecastJson = parseJson(FORECAST_RESPONSE);
        forecastCache.put(HOURLY_URL,
                new CachedForecast(forecastJson, now.plus(Duration.ofMinutes(15)), now));

        WeatherData result = service.getWeatherForecast(LAT, LON, DATE, TIME);

        assertThat(result.getCondition()).isEqualTo("Sunny");
        mockServer.verify();
    }

    @Test
    void staleForecastWithUpstreamError_servesStaleWithinMaxAge() {
        when(gridpointRepository.findContainingPoint(LON, LAT))
                .thenReturn(Optional.of(freshGridpoint()));

        // Cached entry is past freshness (freshUntil 1 hr ago) but only 2 hr
        // old, well within the 6-hour stale-max set in setUp().
        Instant now = fixedClock.instant();
        JsonNode forecastJson = parseJson(FORECAST_RESPONSE);
        forecastCache.put(HOURLY_URL,
                new CachedForecast(forecastJson,
                        now.minus(Duration.ofHours(1)),
                        now.minus(Duration.ofHours(2))));

        mockServer.expect(requestTo(HOURLY_URL))
                .andRespond(withServerError());

        WeatherData result = service.getWeatherForecast(LAT, LON, DATE, TIME);

        assertThat(result.getCondition()).isEqualTo("Sunny");
        mockServer.verify();
    }

    @Test
    void staleForecastBeyondMaxAge_failsWhenUpstreamErrors() {
        when(gridpointRepository.findContainingPoint(LON, LAT))
                .thenReturn(Optional.of(freshGridpoint()));

        Instant now = fixedClock.instant();
        JsonNode forecastJson = parseJson(FORECAST_RESPONSE);
        // 7 hours old — past the 6-hour stale-max set in setUp().
        forecastCache.put(HOURLY_URL,
                new CachedForecast(forecastJson,
                        now.minus(Duration.ofHours(6)),
                        now.minus(Duration.ofHours(7))));

        mockServer.expect(requestTo(HOURLY_URL))
                .andRespond(withServerError());

        WeatherData result = service.getWeatherForecast(LAT, LON, DATE, TIME);

        assertThat(result.hasError()).isTrue();
        assertThat(result.getCondition()).isNull();
        mockServer.verify();
    }

    @Test
    void staleGridpointEntry_refreshSuccessReturnsRefreshedUrl() {
        // Stale by 91 days (gridpoint-refresh-days set to 90 in setUp).
        NwsGridpoint stale = freshGridpoint();
        stale.setFetchedAt(LocalDateTime.now(fixedClock).minusDays(91));
        when(gridpointRepository.findContainingPoint(LON, LAT)).thenReturn(Optional.of(stale));

        mockServer.expect(requestTo(POINTS_URL))
                .andRespond(withSuccess(POINTS_RESPONSE, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(HOURLY_URL))
                .andRespond(withSuccess(FORECAST_RESPONSE, MediaType.APPLICATION_JSON));

        WeatherData result = service.getWeatherForecast(LAT, LON, DATE, TIME);

        assertThat(result.getCondition()).isEqualTo("Sunny");
        verify(gridpointRepository).upsert(
                eq("BOU"), eq(62), eq(61),
                argThat(wkt -> wkt != null && wkt.startsWith("POLYGON")),
                eq(FORECAST_URL), eq(HOURLY_URL),
                any(LocalDateTime.class));
        mockServer.verify();
    }

    @Test
    void staleGridpointEntry_refreshFailureServesStaleUrl() {
        NwsGridpoint stale = freshGridpoint();
        stale.setFetchedAt(LocalDateTime.now(fixedClock).minusDays(91));
        when(gridpointRepository.findContainingPoint(LON, LAT)).thenReturn(Optional.of(stale));

        mockServer.expect(requestTo(POINTS_URL)).andRespond(withServerError());
        mockServer.expect(requestTo(HOURLY_URL))
                .andRespond(withSuccess(FORECAST_RESPONSE, MediaType.APPLICATION_JSON));

        WeatherData result = service.getWeatherForecast(LAT, LON, DATE, TIME);

        assertThat(result.getCondition()).isEqualTo("Sunny");
        mockServer.verify();
    }

    private static NwsGridpoint freshGridpoint() {
        GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 4326);
        Polygon polygon = gf.createPolygon(new Coordinate[]{
                new Coordinate(-105.27, 39.99),
                new Coordinate(-105.24, 39.99),
                new Coordinate(-105.24, 40.02),
                new Coordinate(-105.27, 40.02),
                new Coordinate(-105.27, 39.99)
        });
        polygon.setSRID(4326);
        return new NwsGridpoint("BOU", 62, 61, polygon, FORECAST_URL, HOURLY_URL,
                LocalDateTime.parse("2030-01-01T00:00:00"));
    }

    private static JsonNode parseJson(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
