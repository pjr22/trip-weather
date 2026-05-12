package com.pjr22.tripweather.routing;

import com.pjr22.tripweather.model.LoaderRun;
import com.pjr22.tripweather.model.LoaderRun.TriggerType;
import com.pjr22.tripweather.model.PbfFile;
import com.pjr22.tripweather.repository.PbfFileRepository;
import com.pjr22.tripweather.repository.RoutingCoverageRepository;
import com.pjr22.tripweather.service.LoaderRunRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Phase 2c: the loader now resolves the .poly URL from a pbf_files row's
 * geofabrik_url (swap -latest.osm.pbf → .poly), pulls one
 * routing_coverage row per pbf via upsertPolygon, and leaves the row's
 * enabled flag untouched (it's the admin's manual toggle).
 */
@ExtendWith(MockitoExtension.class)
class GeofabrikCoverageLoaderTest {

    private static final String FIXTURE_POLY = """
            colorado
            1
                -109.05  41.00
                -102.04  41.00
                -102.04  37.00
                -109.05  37.00
                -109.05  41.00
            END
            END
            """;

    private static final String PBF_URL_COLORADO =
            "https://download.geofabrik.de/north-america/us/colorado-latest.osm.pbf";
    private static final String EXPECTED_POLY_URL_COLORADO =
            "https://download.geofabrik.de/north-america/us/colorado.poly";

    @Mock private PbfFileRepository pbfFileRepository;
    @Mock private RoutingCoverageRepository repository;
    @Mock private LoaderRunRecorder recorder;

    private MockRestServiceServer mockServer;
    private RestClient geofabrikRestClient;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        // Phase 2c: the loader passes absolute URIs (derived from each pbf
        // row's geofabrik_url), so the RestClient is created without a base
        // URL. MockRestServiceServer matches the full absolute URL.
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        geofabrikRestClient = builder.build();
        fixedClock = Clock.fixed(Instant.parse("2030-06-01T12:00:00Z"), ZoneOffset.UTC);
    }

    private GeofabrikCoverageLoader newLoader() {
        return new GeofabrikCoverageLoader(
                geofabrikRestClient, pbfFileRepository, repository, fixedClock, recorder);
    }

    private PbfFile colorado() {
        PbfFile pbf = new PbfFile();
        pbf.setPbfName("colorado");
        pbf.setGeofabrikUrl(PBF_URL_COLORADO);
        return pbf;
    }

    private LoaderRun fakeRun(String pbfName, TriggerType trigger) {
        LoaderRun run = new LoaderRun();
        run.setId(1L);
        run.setLoaderName(GeofabrikCoverageLoader.loaderName(pbfName));
        run.setTriggerType(trigger);
        run.setStatus(LoaderRun.Status.RUNNING);
        run.setStartedAt(ZonedDateTime.now());
        return run;
    }

    @Test
    void refresh_throws_when_pbf_row_is_missing() {
        when(pbfFileRepository.findById("colorado")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> newLoader().refresh("colorado", TriggerType.MANUAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No pbf_files row named 'colorado'");
        // No recorder.start should fire when the pre-check fails — we don't
        // want a phantom RUNNING row for a name that doesn't exist.
        verify(recorder, never()).start(anyString(), any(TriggerType.class));
    }

    @Test
    void refresh_fetches_polygon_andUpsertsAndRecordsSuccess() {
        when(pbfFileRepository.findById("colorado")).thenReturn(Optional.of(colorado()));
        when(recorder.start(eq(GeofabrikCoverageLoader.loaderName("colorado")),
                eq(TriggerType.MANUAL)))
                .thenReturn(fakeRun("colorado", TriggerType.MANUAL));
        mockServer.expect(requestTo(EXPECTED_POLY_URL_COLORADO))
                .andRespond(withSuccess(FIXTURE_POLY, MediaType.TEXT_PLAIN));

        newLoader().refresh("colorado", TriggerType.MANUAL);

        ArgumentCaptor<String> wktCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).upsertPolygon(eq("colorado"), wktCaptor.capture(),
                eq(LocalDateTime.now(fixedClock.withZone(ZoneId.systemDefault()))));
        // WKT should be a MULTIPOLYGON (PolyParser always wraps; the
        // dispatcher's spatial query relies on this shape).
        assertThat(wktCaptor.getValue()).startsWith("MULTIPOLYGON");
        // Recorder transitions: RUNNING (MANUAL) → SUCCESS with rows=1.
        verify(recorder).success(any(LoaderRun.class), eq(1L));
    }

    @Test
    void refresh_recordsFail_and_propagates_on_upstream_error() {
        when(pbfFileRepository.findById("colorado")).thenReturn(Optional.of(colorado()));
        when(recorder.start(eq(GeofabrikCoverageLoader.loaderName("colorado")),
                eq(TriggerType.CRON)))
                .thenReturn(fakeRun("colorado", TriggerType.CRON));
        mockServer.expect(requestTo(EXPECTED_POLY_URL_COLORADO))
                .andRespond(withServerError());

        assertThatThrownBy(() -> newLoader().refresh("colorado", TriggerType.CRON))
                .isInstanceOf(RuntimeException.class);

        verify(repository, never()).upsertPolygon(anyString(), anyString(), any());
        verify(recorder).fail(any(LoaderRun.class), any(Throwable.class));
    }

    @Test
    void refresh_propagatesRunInProgress() {
        // The recorder's concurrency guard surfaces as RunInProgressException
        // — refresh should let it propagate so the controller can map to 409.
        when(pbfFileRepository.findById("colorado")).thenReturn(Optional.of(colorado()));
        when(recorder.start(eq(GeofabrikCoverageLoader.loaderName("colorado")),
                eq(TriggerType.MANUAL)))
                .thenThrow(new LoaderRunRecorder.RunInProgressException(
                        GeofabrikCoverageLoader.loaderName("colorado")));

        assertThatThrownBy(() -> newLoader().refresh("colorado", TriggerType.MANUAL))
                .isInstanceOf(LoaderRunRecorder.RunInProgressException.class);
        verify(repository, never()).upsertPolygon(anyString(), anyString(), any());
    }

    @Test
    void derivePolyUrl_swaps_pbf_suffix_for_poly() {
        // The conversion is the load-bearing piece for "one pbf, one poly".
        assertThat(GeofabrikCoverageLoader.derivePolyUrl(
                "https://download.geofabrik.de/north-america/us-west-latest.osm.pbf"))
                .isEqualTo(URI.create(
                        "https://download.geofabrik.de/north-america/us-west.poly"));
        assertThat(GeofabrikCoverageLoader.derivePolyUrl(
                "https://download.geofabrik.de/north-america/us/colorado-latest.osm.pbf"))
                .isEqualTo(URI.create(
                        "https://download.geofabrik.de/north-america/us/colorado.poly"));
    }

    @Test
    void derivePolyUrl_throws_when_pbf_url_doesnt_follow_geofabrik_convention() {
        assertThatThrownBy(() -> GeofabrikCoverageLoader.derivePolyUrl(
                "https://example.com/random-file.bin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("-latest.osm.pbf");
    }

    // ---- Mockito helpers --------------------------------------------------

    private static String anyString()    { return org.mockito.ArgumentMatchers.anyString(); }
    private static <T> T any()           { return org.mockito.ArgumentMatchers.any(); }
    private static <T> T any(Class<T> c) { return org.mockito.ArgumentMatchers.any(c); }
    private static <T> T eq(T v)         { return org.mockito.ArgumentMatchers.eq(v); }
}
