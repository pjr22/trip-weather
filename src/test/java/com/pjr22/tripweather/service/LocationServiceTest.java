package com.pjr22.tripweather.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pjr22.tripweather.dto.LocationResolution;
import com.pjr22.tripweather.model.GeoPoint;
import com.pjr22.tripweather.model.GeocodeReverseCache;
import com.pjr22.tripweather.model.LocationData;
import com.pjr22.tripweather.model.SnappedPoint;
import com.pjr22.tripweather.repository.GeocodeReverseCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class LocationServiceTest {

    private static final String API_KEY = "test-key";
    private static final double LAT = 40.0;
    private static final double LON = -105.25;
    private static final double RADIUS_METERS = 15.0;
    private static final long REFRESH_DAYS = 365;

    private static final String REVERSE_RESPONSE = """
            {
              "type": "FeatureCollection",
              "features": [{
                "type": "Feature",
                "properties": {
                  "address_line1": "123 Main St",
                  "city": "Boulder",
                  "state_code": "CO",
                  "formatted": "123 Main St, Boulder, CO"
                },
                "geometry": {
                  "type": "Point",
                  "coordinates": [-105.25, 40.0]
                }
              }]
            }
            """;

    private static final String FORWARD_RESPONSE = """
            {
              "type": "FeatureCollection",
              "features": [{
                "properties": { "formatted": "Boulder, CO, United States" },
                "geometry": { "type": "Point", "coordinates": [-105.27, 40.01] }
              }]
            }
            """;

    @Mock private GeocodeReverseCacheRepository reverseCacheRepository;
    @Mock private RouteService routeService;

    private RestClient restClient;
    private MockRestServiceServer mockServer;
    private Cache<String, JsonNode> forwardCache;
    private Clock fixedClock;
    private ObjectMapper objectMapper;
    private LocationService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.geoapify.com/v1");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();

        forwardCache = Caffeine.newBuilder().maximumSize(100).build();
        fixedClock = Clock.fixed(Instant.parse("2030-06-01T12:00:00Z"), ZoneOffset.UTC);
        objectMapper = new ObjectMapper();

        service = new LocationService(API_KEY, restClient, routeService, objectMapper,
                reverseCacheRepository, forwardCache, fixedClock,
                RADIUS_METERS, REFRESH_DAYS);
    }

    @Test
    void reverseGeocode_cacheHit_skipsApi() {
        GeocodeReverseCache cached = freshCachedEntry();
        when(reverseCacheRepository.findNearest(LON, LAT, RADIUS_METERS))
                .thenReturn(Optional.of(cached));
        when(routeService.resolveLocation(LAT, LON)).thenReturn(routableResolution());

        LocationData result = service.reverseGeocode(LAT, LON);

        assertThat(result).isNotNull();
        assertThat(result.getFeatures()).hasSize(1);
        // Original/snapped fields populated from the resolution; the geometry
        // coordinates are no longer mutated.
        assertThat(result.getOriginal().getLat()).isEqualTo(LAT);
        assertThat(result.getOriginal().getLon()).isEqualTo(LON);
        assertThat(result.getSnapped().isRoutable()).isTrue();
        assertThat(result.getSnapped().getElevation()).isEqualTo(1655.0);
        verify(reverseCacheRepository, never()).save(any(GeocodeReverseCache.class));
        mockServer.verify();
    }

    @Test
    void reverseGeocode_cacheMiss_callsApiAndPersists() {
        when(reverseCacheRepository.findNearest(LON, LAT, RADIUS_METERS))
                .thenReturn(Optional.empty());
        when(routeService.resolveLocation(LAT, LON)).thenReturn(routableResolution());

        mockServer.expect(requestTo(String.format(
                "https://api.geoapify.com/v1/geocode/reverse?lat=%.6f&lon=%.6f&apiKey=%s",
                LAT, LON, API_KEY)))
                .andRespond(withSuccess(REVERSE_RESPONSE, MediaType.APPLICATION_JSON));

        LocationData result = service.reverseGeocode(LAT, LON);

        assertThat(result).isNotNull();
        assertThat(result.getFeatures()).hasSize(1);
        assertThat(result.getSnapped().getElevation()).isEqualTo(1655.0);
        ArgumentCaptor<GeocodeReverseCache> saved = ArgumentCaptor.forClass(GeocodeReverseCache.class);
        verify(reverseCacheRepository).save(saved.capture());
        GeocodeReverseCache stored = saved.getValue();
        assertThat(stored.getPoint().getY()).isEqualTo(LAT);
        assertThat(stored.getPoint().getX()).isEqualTo(LON);
        assertThat(stored.getResponseJson()).contains("Boulder");
        mockServer.verify();
    }

    @Test
    void reverseGeocode_staleEntryWithUpstreamFailure_servesStale() {
        GeocodeReverseCache stale = freshCachedEntry();
        // Past the 365-day refresh window.
        stale.setFetchedAt(LocalDateTime.now(fixedClock).minusDays(400));
        when(reverseCacheRepository.findNearest(LON, LAT, RADIUS_METERS))
                .thenReturn(Optional.of(stale));
        when(routeService.resolveLocation(LAT, LON)).thenReturn(routableResolution());

        mockServer.expect(requestTo(String.format(
                "https://api.geoapify.com/v1/geocode/reverse?lat=%.6f&lon=%.6f&apiKey=%s",
                LAT, LON, API_KEY)))
                .andRespond(withServerError());

        LocationData result = service.reverseGeocode(LAT, LON);

        assertThat(result).isNotNull();
        assertThat(result.getFeatures().get(0).getProperties().getCity()).isEqualTo("Boulder");
        assertThat(result.getSnapped().getElevation()).isEqualTo(1655.0);
        mockServer.verify();
    }

    private static LocationResolution routableResolution() {
        return new LocationResolution(
                new GeoPoint(LAT, LON),
                new SnappedPoint(LAT, LON, 1655.0, true));
    }

    @Test
    void searchLocations_cacheHit_skipsApi() throws Exception {
        JsonNode preCached = objectMapper.readTree(FORWARD_RESPONSE);
        forwardCache.put("boulder, co", preCached);

        JsonNode result = service.searchLocations("Boulder, CO");

        assertThat(result).isEqualTo(preCached);
        mockServer.verify();
    }

    @Test
    void searchLocations_cacheMiss_callsApiAndPopulatesCache() {
        // Spring's RestClient URL-encodes the space in "Boulder, CO" before sending.
        mockServer.expect(requestTo(String.format(
                "https://api.geoapify.com/v1/geocode/search?apiKey=%s&text=Boulder,%%20CO", API_KEY)))
                .andRespond(withSuccess(FORWARD_RESPONSE, MediaType.APPLICATION_JSON));

        JsonNode result = service.searchLocations("Boulder, CO");

        assertThat(result).isNotNull();
        assertThat(forwardCache.getIfPresent("boulder, co")).isNotNull();
        verifyNoInteractions(routeService);
        mockServer.verify();
    }

    @Test
    void searchLocations_normalizesKey_caseAndWhitespaceInsensitive() throws Exception {
        JsonNode preCached = objectMapper.readTree(FORWARD_RESPONSE);
        forwardCache.put("boulder, co", preCached);

        JsonNode upper = service.searchLocations("  BOULDER, CO  ");
        JsonNode mixed = service.searchLocations("Boulder, Co");

        assertThat(upper).isEqualTo(preCached);
        assertThat(mixed).isEqualTo(preCached);
        mockServer.verify();
    }

    private GeocodeReverseCache freshCachedEntry() {
        GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 4326);
        Point point = gf.createPoint(new Coordinate(LON, LAT));
        point.setSRID(4326);
        return new GeocodeReverseCache(1L, point, REVERSE_RESPONSE,
                LocalDateTime.now(fixedClock).minusDays(10));
    }
}
