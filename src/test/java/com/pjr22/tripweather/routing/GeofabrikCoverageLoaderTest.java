package com.pjr22.tripweather.routing;

import com.pjr22.tripweather.model.LoaderRun;
import com.pjr22.tripweather.model.LoaderRun.TriggerType;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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

    @Mock private RoutingCoverageRepository repository;
    @Mock private LoaderRunRecorder recorder;

    private MockRestServiceServer mockServer;
    private RestClient geofabrikRestClient;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://download.geofabrik.de/north-america/us");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        geofabrikRestClient = builder.build();
        fixedClock = Clock.fixed(Instant.parse("2030-06-01T12:00:00Z"), ZoneOffset.UTC);
    }

    private LoaderRun fakeRun(String region, TriggerType trigger) {
        LoaderRun run = new LoaderRun();
        run.setId(1L);
        run.setLoaderName(GeofabrikCoverageLoader.loaderName(region));
        run.setTriggerType(trigger);
        run.setStatus(LoaderRun.Status.RUNNING);
        run.setStartedAt(ZonedDateTime.now());
        return run;
    }

    @Test
    void seedMissingRegions_skips_existing_rows() {
        when(repository.existsById("colorado")).thenReturn(true);

        GeofabrikCoverageLoader loader = new GeofabrikCoverageLoader(
                geofabrikRestClient, repository, fixedClock, recorder, "colorado");
        loader.seedMissingRegions();

        // No HTTP request, no upsert.
        mockServer.verify();
        verify(repository, never()).upsert(anyString(), anyString(), any());
        // Recorder is never called for already-seeded regions — the
        // skip-existing path bails before any record-the-run logic runs.
        verify(recorder, never()).start(anyString(), any(TriggerType.class));
    }

    @Test
    void seedMissingRegions_fetches_recordsBootstrap_andUpserts_when_row_absent() {
        when(repository.existsById("colorado")).thenReturn(false);
        when(recorder.start(eq(GeofabrikCoverageLoader.loaderName("colorado")),
                eq(TriggerType.BOOTSTRAP)))
                .thenReturn(fakeRun("colorado", TriggerType.BOOTSTRAP));
        mockServer.expect(requestTo(
                        "https://download.geofabrik.de/north-america/us/colorado.poly"))
                .andRespond(withSuccess(FIXTURE_POLY, MediaType.TEXT_PLAIN));

        GeofabrikCoverageLoader loader = new GeofabrikCoverageLoader(
                geofabrikRestClient, repository, fixedClock, recorder, "colorado");
        loader.seedMissingRegions();

        ArgumentCaptor<String> wktCaptor = ArgumentCaptor.forClass(String.class);
        verify(repository).upsert(eq("colorado"), wktCaptor.capture(),
                eq(java.time.LocalDateTime.now(fixedClock.withZone(java.time.ZoneId.systemDefault()))));
        // WKT should be a MULTIPOLYGON (PolyParser always wraps; the
        // dispatcher's spatial query relies on this shape).
        assertThat(wktCaptor.getValue()).startsWith("MULTIPOLYGON");
        // Recorder transitions: RUNNING (BOOTSTRAP) → SUCCESS with rows=1.
        verify(recorder).success(any(LoaderRun.class), eq(1L));
    }

    @Test
    void seedMissingRegions_swallows_per_region_failures_and_records_fail() {
        when(repository.existsById("colorado")).thenReturn(false);
        when(recorder.start(eq(GeofabrikCoverageLoader.loaderName("colorado")),
                eq(TriggerType.BOOTSTRAP)))
                .thenReturn(fakeRun("colorado", TriggerType.BOOTSTRAP));
        mockServer.expect(requestTo(
                        "https://download.geofabrik.de/north-america/us/colorado.poly"))
                .andRespond(withServerError());

        GeofabrikCoverageLoader loader = new GeofabrikCoverageLoader(
                geofabrikRestClient, repository, fixedClock, recorder, "colorado");
        // Should not throw — failure is logged and the dispatcher safely
        // treats the region as uncovered until the next refresh. But the
        // loader_runs row should still flip to FAIL so the admin sees it.
        loader.seedMissingRegions();

        verify(repository, never()).upsert(anyString(), anyString(), any());
        verify(recorder).fail(any(LoaderRun.class), any(Throwable.class));
    }

    @Test
    void refresh_throws_for_unknown_region() {
        GeofabrikCoverageLoader loader = new GeofabrikCoverageLoader(
                geofabrikRestClient, repository, fixedClock, recorder, "colorado");

        assertThatThrownBy(() -> loader.refresh("wyoming", TriggerType.MANUAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in trip.routing.local-regions");
    }

    @Test
    void refresh_re_fetches_even_if_row_exists_andRecordsTrigger() {
        // refresh() bypasses the existsById check on purpose — it's the
        // post-pbf-swap entry point; the polygon may have changed.
        when(recorder.start(eq(GeofabrikCoverageLoader.loaderName("colorado")),
                eq(TriggerType.MANUAL)))
                .thenReturn(fakeRun("colorado", TriggerType.MANUAL));
        mockServer.expect(requestTo(
                        "https://download.geofabrik.de/north-america/us/colorado.poly"))
                .andRespond(withSuccess(FIXTURE_POLY, MediaType.TEXT_PLAIN));

        GeofabrikCoverageLoader loader = new GeofabrikCoverageLoader(
                geofabrikRestClient, repository, fixedClock, recorder, "colorado");
        loader.refresh("colorado", TriggerType.MANUAL);

        verify(repository).upsert(eq("colorado"), startsWithMultipolygon(),
                any(java.time.LocalDateTime.class));
        verify(recorder).success(any(LoaderRun.class), eq(1L));
    }

    @Test
    void refresh_propagatesRunInProgress() {
        // The recorder's concurrency guard surfaces as RunInProgressException
        // — refresh should let it propagate so the controller can map to 409.
        when(recorder.start(eq(GeofabrikCoverageLoader.loaderName("colorado")),
                eq(TriggerType.MANUAL)))
                .thenThrow(new LoaderRunRecorder.RunInProgressException(
                        GeofabrikCoverageLoader.loaderName("colorado")));

        GeofabrikCoverageLoader loader = new GeofabrikCoverageLoader(
                geofabrikRestClient, repository, fixedClock, recorder, "colorado");

        assertThatThrownBy(() -> loader.refresh("colorado", TriggerType.MANUAL))
                .isInstanceOf(LoaderRunRecorder.RunInProgressException.class);
        verify(repository, never()).upsert(anyString(), anyString(), any());
    }

    // ---- Mockito helpers --------------------------------------------------

    private static String anyString()    { return org.mockito.ArgumentMatchers.anyString(); }
    private static <T> T any()           { return org.mockito.ArgumentMatchers.any(); }
    private static <T> T any(Class<T> c) { return org.mockito.ArgumentMatchers.any(c); }
    private static <T> T eq(T v)         { return org.mockito.ArgumentMatchers.eq(v); }

    /** Argument matcher that asserts the captured string starts with MULTIPOLYGON. */
    private static String startsWithMultipolygon() {
        return org.mockito.ArgumentMatchers.argThat(s ->
                s != null && s.startsWith("MULTIPOLYGON"));
    }
}
