package com.pjr22.tripweather.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pjr22.tripweather.model.LocationData;
import com.pjr22.tripweather.model.OrsResponseCache;
import com.pjr22.tripweather.model.RouteData;
import com.pjr22.tripweather.repository.OrsResponseCacheRepository;
import com.pjr22.tripweather.repository.RoutingCoverageRepository;
import com.pjr22.tripweather.routing.LocalOrsClient;
import com.pjr22.tripweather.routing.PublicOrsClient;
import com.pjr22.tripweather.routing.RoutingDispatcher;
import com.pjr22.tripweather.routing.RoutingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class RouteServiceTest {

    private static final String API_KEY = "ors-test-key";
    private static final double LAT = 39.7392;
    private static final double LON = -104.9903;

    private static final String ELEVATION_RESPONSE = """
            {
              "type": "Feature",
              "geometry": {
                "type": "Point",
                "coordinates": [-104.9903, 39.7392, 1610.0]
              }
            }
            """;

    private static final String SNAP_RESPONSE = """
            {
              "type": "FeatureCollection",
              "features": [{
                "type": "Feature",
                "properties": { "snapped_distance": 5 },
                "geometry": {
                  "type": "Point",
                  "coordinates": [-104.9903, 39.7392]
                }
              }]
            }
            """;

    private static final String DIRECTIONS_RESPONSE = """
            {
              "features": [{
                "geometry": {
                  "type": "LineString",
                  "coordinates": [
                    [-104.9903, 39.7392, 1610.0],
                    [-105.0000, 39.7400, 1620.0]
                  ]
                },
                "properties": {
                  "summary": { "distance": 1234.5, "duration": 60.0 },
                  "segments": [{
                    "distance": 1234.5,
                    "duration": 60.0,
                    "steps": []
                  }]
                }
              }]
            }
            """;

    @Mock private OrsResponseCacheRepository cacheRepository;
    @Mock private RoutingCoverageRepository coverageRepository;
    @Mock private ObjectProvider<LocalOrsClient> localProvider;

    private RestClient restClient;
    private MockRestServiceServer mockServer;
    private Clock fixedClock;
    private ObjectMapper objectMapper;
    private RouteService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.openrouteservice.org");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();

        fixedClock = Clock.fixed(Instant.parse("2030-06-01T12:00:00Z"), ZoneOffset.UTC);
        // Match Spring Boot's auto-configured default — production tolerates unknown fields.
        objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // No local ORS bean -> dispatcher takes the disabled-fast-path and goes
        // straight to public for every call, which is what these tests assert.
        // Coverage repo is therefore never consulted.
        PublicOrsClient publicClient = new PublicOrsClient(restClient, API_KEY);
        RoutingMetrics metrics = new RoutingMetrics(new SimpleMeterRegistry());
        RoutingDispatcher dispatcher = new RoutingDispatcher(
                publicClient, localProvider, coverageRepository, metrics);

        service = new RouteService(publicClient, dispatcher, objectMapper, cacheRepository,
                fixedClock,
                24L,    // directions ttl hours
                168L,   // directions stale-max hours
                720L,   // snap ttl hours
                2160L,  // snap stale-max hours
                720L,   // elevation ttl hours
                2160L); // elevation stale-max hours
    }

    @Test
    void elevation_cacheHit_skipsApi() {
        OrsResponseCache cached = new OrsResponseCache("hash-elevation", "elevation",
                ELEVATION_RESPONSE, LocalDateTime.now(fixedClock).minusHours(1));
        when(cacheRepository.findById(anyString())).thenReturn(Optional.of(cached));

        Double elevation = service.getElevation(LAT, LON);

        assertThat(elevation).isEqualTo(1610.0);
        verify(cacheRepository, never()).save(any(OrsResponseCache.class));
        mockServer.verify();
    }

    @Test
    void elevation_cacheMiss_callsApiAndPersists() {
        when(cacheRepository.findById(anyString())).thenReturn(Optional.empty());
        mockServer.expect(requestTo("https://api.openrouteservice.org/elevation/point?geometry="
                        + LON + "," + LAT))
                .andRespond(withSuccess(ELEVATION_RESPONSE, MediaType.APPLICATION_JSON));

        Double elevation = service.getElevation(LAT, LON);

        assertThat(elevation).isEqualTo(1610.0);
        ArgumentCaptor<OrsResponseCache> saved = ArgumentCaptor.forClass(OrsResponseCache.class);
        verify(cacheRepository).save(saved.capture());
        assertThat(saved.getValue().getEndpoint()).isEqualTo("elevation");
        assertThat(saved.getValue().getRequestHash()).hasSize(64);
        mockServer.verify();
    }

    @Test
    void elevation_quantizationCollapsesNearbyCoordinates() {
        // Same hash key for two coordinates that round to identical 6-decimal values.
        when(cacheRepository.findById(anyString())).thenReturn(Optional.empty());
        mockServer.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withSuccess(ELEVATION_RESPONSE, MediaType.APPLICATION_JSON));

        service.getElevation(39.73920000001, -104.99030000001);

        ArgumentCaptor<OrsResponseCache> saved = ArgumentCaptor.forClass(OrsResponseCache.class);
        verify(cacheRepository).save(saved.capture());

        // Now look up the saved hash, and verify a second call with effectively-the-same
        // coordinates would be looked up by the same key.
        String savedHash = saved.getValue().getRequestHash();
        when(cacheRepository.findById(savedHash)).thenReturn(Optional.of(saved.getValue()));

        Double elevation = service.getElevation(39.73920000002, -104.99030000002);
        assertThat(elevation).isEqualTo(1610.0);
        mockServer.verify();
    }

    @Test
    void elevation_staleEntryUpstreamFails_servesStaleWithinMax() {
        // Stale by 30h (TTL = 720h, well within stale-max = 2160h).
        OrsResponseCache stale = new OrsResponseCache("hash-elevation", "elevation",
                ELEVATION_RESPONSE, LocalDateTime.now(fixedClock).minusHours(800));
        when(cacheRepository.findById(anyString())).thenReturn(Optional.of(stale));
        mockServer.expect(requestTo(org.hamcrest.Matchers.any(String.class))).andRespond(withServerError());

        Double elevation = service.getElevation(LAT, LON);

        assertThat(elevation).isEqualTo(1610.0);
        mockServer.verify();
    }

    @Test
    void snap_cacheHit_skipsApi() throws Exception {
        OrsResponseCache cached = new OrsResponseCache("hash-snap", "snap",
                SNAP_RESPONSE, LocalDateTime.now(fixedClock).minusHours(1));
        when(cacheRepository.findById(anyString())).thenReturn(Optional.of(cached));

        LocationData result = service.snapToLocation(LAT, LON);

        assertThat(result).isNotNull();
        assertThat(result.getFeatures()).hasSize(1);
        verify(cacheRepository, never()).save(any(OrsResponseCache.class));
        mockServer.verify();
    }

    @Test
    void calculateRoute_cacheHit_skipsApi() {
        OrsResponseCache cached = new OrsResponseCache("hash-directions", "directions",
                DIRECTIONS_RESPONSE, LocalDateTime.now(fixedClock).minusHours(1));
        when(cacheRepository.findById(anyString())).thenReturn(Optional.of(cached));

        ZonedDateTime departure = ZonedDateTime.now(fixedClock).plusHours(1);
        RouteData result = service.calculateRoute(twoWaypoints(), departure, List.of(0, 0));

        assertThat(result).isNotNull();
        assertThat(result.getDistance()).isEqualTo(1234.5);
        assertThat(result.getGeometry()).hasSize(2);
        verify(cacheRepository, never()).save(any(OrsResponseCache.class));
        mockServer.verify();
    }

    @Test
    void calculateRoute_cacheMiss_callsApiAndPersists() {
        when(cacheRepository.findById(anyString())).thenReturn(Optional.empty());
        mockServer.expect(requestTo("https://api.openrouteservice.org/v2/directions/driving-car/geojson"))
                .andRespond(withSuccess(DIRECTIONS_RESPONSE, MediaType.APPLICATION_JSON));

        ZonedDateTime departure = ZonedDateTime.now(fixedClock).plusHours(1);
        RouteData result = service.calculateRoute(twoWaypoints(), departure, List.of(0, 0));

        assertThat(result.getDistance()).isEqualTo(1234.5);
        ArgumentCaptor<OrsResponseCache> saved = ArgumentCaptor.forClass(OrsResponseCache.class);
        verify(cacheRepository).save(saved.capture());
        assertThat(saved.getValue().getEndpoint()).isEqualTo("directions");
        mockServer.verify();
    }

    @Test
    void calculateRoute_sameWaypointsProduceSameCacheKey() {
        when(cacheRepository.findById(anyString())).thenReturn(Optional.empty());
        mockServer.expect(requestTo("https://api.openrouteservice.org/v2/directions/driving-car/geojson"))
                .andRespond(withSuccess(DIRECTIONS_RESPONSE, MediaType.APPLICATION_JSON));

        ZonedDateTime departure = ZonedDateTime.now(fixedClock).plusHours(1);
        service.calculateRoute(twoWaypoints(), departure, List.of(0, 0));
        ArgumentCaptor<OrsResponseCache> first = ArgumentCaptor.forClass(OrsResponseCache.class);
        verify(cacheRepository).save(first.capture());

        // Different times of day, different waypoint name labels — but the same geographic
        // input. Cache key must be identical.
        when(cacheRepository.findById(first.getValue().getRequestHash()))
                .thenReturn(Optional.of(first.getValue()));

        List<RouteService.RouteRequest.Waypoint> sameCoordsDifferentNames = List.of(
                new RouteService.RouteRequest.Waypoint(LAT, LON, "Different Name", "America/Denver"),
                new RouteService.RouteRequest.Waypoint(LAT + 0.01, LON - 0.01, "Other", "America/Denver")
        );
        ZonedDateTime laterDeparture = departure.plusHours(5);
        RouteData second = service.calculateRoute(sameCoordsDifferentNames, laterDeparture, List.of(0, 0));

        assertThat(second.getDistance()).isEqualTo(1234.5);
        mockServer.verify();
    }

    private static List<RouteService.RouteRequest.Waypoint> twoWaypoints() {
        return List.of(
                new RouteService.RouteRequest.Waypoint(LAT, LON, "Start", "America/Denver"),
                new RouteService.RouteRequest.Waypoint(LAT + 0.01, LON - 0.01, "End", "America/Denver")
        );
    }

}
