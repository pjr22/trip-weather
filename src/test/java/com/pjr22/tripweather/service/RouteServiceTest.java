package com.pjr22.tripweather.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pjr22.tripweather.dto.LocationResolution;
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
import static org.mockito.Mockito.times;
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

    private static final String SNAP_EMPTY_RESPONSE = """
            { "type": "FeatureCollection", "features": [] }
            """;

    private static final String ELEVATION_LOOKUP_RESPONSE = """
            {
              "type": "FeatureCollection",
              "features": [{
                "type": "Feature",
                "geometry": {
                  "type": "LineString",
                  "coordinates": [[-104.9903, 39.7392, 1610.0]]
                },
                "properties": { "summary": { "distance": 0.0, "duration": 0.0 } }
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
    private SimpleMeterRegistry meterRegistry;

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
        meterRegistry = new SimpleMeterRegistry();
        RoutingMetrics metrics = new RoutingMetrics(meterRegistry);
        RoutingDispatcher dispatcher = new RoutingDispatcher(
                publicClient, localProvider, coverageRepository, metrics);

        service = new RouteService(publicClient, dispatcher, objectMapper, cacheRepository,
                new DbCacheMetrics(meterRegistry),
                fixedClock,
                24L,    // directions ttl hours
                168L,   // directions stale-max hours
                720L,   // snap ttl hours
                2160L,  // snap stale-max hours
                720L,   // elevation ttl hours
                2160L); // elevation stale-max hours
    }

    @Test
    void resolve_bothCachesHit_skipsApi() {
        // resolveLocation hits snap cache first, then elevation_lookup cache.
        // No upstream calls expected; both pre-populated entries serve the request.
        OrsResponseCache snapCached = new OrsResponseCache("hash-snap", "snap",
                SNAP_RESPONSE, LocalDateTime.now(fixedClock).minusHours(1));
        OrsResponseCache lookupCached = new OrsResponseCache("hash-lookup", "elevation_lookup",
                ELEVATION_LOOKUP_RESPONSE, LocalDateTime.now(fixedClock).minusHours(1));
        when(cacheRepository.findById(anyString()))
                .thenReturn(Optional.of(snapCached))
                .thenReturn(Optional.of(lookupCached));

        LocationResolution resolution = service.resolveLocation(LAT, LON);

        assertThat(resolution).isNotNull();
        assertThat(resolution.getOriginal().getLat()).isEqualTo(LAT);
        assertThat(resolution.getOriginal().getLon()).isEqualTo(LON);
        assertThat(resolution.getSnapped().isRoutable()).isTrue();
        assertThat(resolution.getSnapped().getElevation()).isEqualTo(1610.0);
        verify(cacheRepository, never()).save(any(OrsResponseCache.class));
        mockServer.verify();
    }

    @Test
    void resolve_bothCachesMiss_callsApisAndPersistsBoth() {
        when(cacheRepository.findById(anyString())).thenReturn(Optional.empty());
        mockServer.expect(requestTo("https://api.openrouteservice.org/v2/snap/driving-car/geojson"))
                .andRespond(withSuccess(SNAP_RESPONSE, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("https://api.openrouteservice.org/v2/directions/driving-car/geojson"))
                .andRespond(withSuccess(ELEVATION_LOOKUP_RESPONSE, MediaType.APPLICATION_JSON));

        LocationResolution resolution = service.resolveLocation(LAT, LON);

        assertThat(resolution.getSnapped().isRoutable()).isTrue();
        assertThat(resolution.getSnapped().getElevation()).isEqualTo(1610.0);

        ArgumentCaptor<OrsResponseCache> saved = ArgumentCaptor.forClass(OrsResponseCache.class);
        verify(cacheRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(OrsResponseCache::getEndpoint)
                .containsExactly("snap", "elevation_lookup");
        mockServer.verify();
    }

    @Test
    void resolve_snapEmpty_fallsBackToPublicTerrainElevation() {
        when(cacheRepository.findById(anyString())).thenReturn(Optional.empty());
        mockServer.expect(requestTo("https://api.openrouteservice.org/v2/snap/driving-car/geojson"))
                .andRespond(withSuccess(SNAP_EMPTY_RESPONSE, MediaType.APPLICATION_JSON));
        // Off-road fallback bypasses the dispatcher; calls public /elevation/point directly.
        // Coordinates serialised with %.6f so URL is deterministic regardless of locale.
        String elevationUrl = "https://api.openrouteservice.org/elevation/point?geometry="
                + String.format(java.util.Locale.ROOT, "%.6f,%.6f", LON, LAT);
        mockServer.expect(requestTo(elevationUrl))
                .andRespond(withSuccess(ELEVATION_RESPONSE, MediaType.APPLICATION_JSON));

        LocationResolution resolution = service.resolveLocation(LAT, LON);

        assertThat(resolution.getSnapped().isRoutable()).isFalse();
        assertThat(resolution.getSnapped().getLat()).isEqualTo(LAT);  // mirrors original
        assertThat(resolution.getSnapped().getLon()).isEqualTo(LON);
        assertThat(resolution.getSnapped().getElevation()).isEqualTo(1610.0);

        ArgumentCaptor<OrsResponseCache> saved = ArgumentCaptor.forClass(OrsResponseCache.class);
        verify(cacheRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(OrsResponseCache::getEndpoint)
                .containsExactly("snap", "elevation");
        mockServer.verify();
    }

    @Test
    void resolve_quantizationCollapsesNearbyCoordinates() {
        // Two coords that round to identical 6-decimal values must produce
        // identical snap+lookup cache keys, so the second call is a full hit.
        when(cacheRepository.findById(anyString())).thenReturn(Optional.empty());
        mockServer.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withSuccess(SNAP_RESPONSE, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withSuccess(ELEVATION_LOOKUP_RESPONSE, MediaType.APPLICATION_JSON));

        service.resolveLocation(39.73920000001, -104.99030000001);

        ArgumentCaptor<OrsResponseCache> saved = ArgumentCaptor.forClass(OrsResponseCache.class);
        verify(cacheRepository, times(2)).save(saved.capture());
        OrsResponseCache snapEntry = saved.getAllValues().stream()
                .filter(e -> e.getEndpoint().equals("snap")).findFirst().orElseThrow();
        OrsResponseCache lookupEntry = saved.getAllValues().stream()
                .filter(e -> e.getEndpoint().equals("elevation_lookup")).findFirst().orElseThrow();

        // Re-stub findById to return saved entries by hash; second call must hit both.
        when(cacheRepository.findById(snapEntry.getRequestHash())).thenReturn(Optional.of(snapEntry));
        when(cacheRepository.findById(lookupEntry.getRequestHash())).thenReturn(Optional.of(lookupEntry));

        LocationResolution second = service.resolveLocation(39.73920000002, -104.99030000002);
        assertThat(second.getSnapped().getElevation()).isEqualTo(1610.0);
        mockServer.verify();
    }

    @Test
    void resolve_staleEntriesAndUpstreamFails_servesStale() {
        // Both caches stale (800h, TTL=720h, stale-max=2160h). Upstream errors
        // for refresh; both stale-on-error paths return cached payloads.
        OrsResponseCache staleSnap = new OrsResponseCache("hash-snap", "snap",
                SNAP_RESPONSE, LocalDateTime.now(fixedClock).minusHours(800));
        OrsResponseCache staleLookup = new OrsResponseCache("hash-lookup", "elevation_lookup",
                ELEVATION_LOOKUP_RESPONSE, LocalDateTime.now(fixedClock).minusHours(800));
        when(cacheRepository.findById(anyString()))
                .thenReturn(Optional.of(staleSnap))
                .thenReturn(Optional.of(staleLookup));
        mockServer.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withServerError());
        mockServer.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withServerError());

        LocationResolution resolution = service.resolveLocation(LAT, LON);

        assertThat(resolution.getSnapped().isRoutable()).isTrue();
        assertThat(resolution.getSnapped().getElevation()).isEqualTo(1610.0);
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

    /**
     * Step 3 of ADMIN_CONSOLE.md phase 3: instrumenting the DB cache. A
     * cache hit on snap + elevation_lookup should record two
     * {@code cache.gets{result=hit}} increments under the
     * {@code ors-snap} and {@code ors-elevation-lookup} cache tags, with no
     * miss counters firing.
     */
    @Test
    void cacheHitOnSnapAndElevationLookup_recordsHitCounters() {
        // Cache rows pre-loaded; the service path is the same as
        // resolve_bothCachesHit_skipsApi.
        OrsResponseCache snapEntry = new OrsResponseCache("snap-hash", "snap",
                """
                {"type":"FeatureCollection","features":[{"geometry":{"coordinates":[-104.9903,39.7392]},"properties":{"distance":0}}]}
                """, LocalDateTime.now(fixedClock));
        OrsResponseCache elevEntry = new OrsResponseCache("elev-hash", "elevation_lookup",
                ELEVATION_RESPONSE, LocalDateTime.now(fixedClock));
        when(cacheRepository.findById(anyString()))
                .thenReturn(Optional.of(snapEntry))   // snap lookup
                .thenReturn(Optional.of(elevEntry));  // elevation_lookup lookup

        service.resolveLocation(LAT, LON);

        assertThat(meterRegistry.find("cache.gets")
                .tag("cache", "ors-snap").tag("result", "hit").counter())
                .isNotNull()
                .extracting(c -> (long) c.count()).isEqualTo(1L);
        assertThat(meterRegistry.find("cache.gets")
                .tag("cache", "ors-elevation-lookup").tag("result", "hit").counter())
                .isNotNull()
                .extracting(c -> (long) c.count()).isEqualTo(1L);
        // No miss counters should have been touched.
        assertThat(meterRegistry.find("cache.gets")
                .tag("cache", "ors-snap").tag("result", "miss").counter())
                .isNull();
    }

}
