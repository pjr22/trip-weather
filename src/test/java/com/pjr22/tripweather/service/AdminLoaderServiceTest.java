package com.pjr22.tripweather.service;

import com.pjr22.tripweather.dto.LoaderSummaryDto;
import com.pjr22.tripweather.model.LoaderRun;
import com.pjr22.tripweather.model.LoaderRun.Status;
import com.pjr22.tripweather.model.LoaderRun.TriggerType;
import com.pjr22.tripweather.model.PbfFile;
import com.pjr22.tripweather.repository.LoaderRunRepository;
import com.pjr22.tripweather.repository.PbfFileRepository;
import com.pjr22.tripweather.routing.GeofabrikCoverageLoader;
import com.pjr22.tripweather.scheduler.GuestRouteCleanupJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminLoaderService}. Covers loader-list shape,
 * history pagination, the synchronous 409 pre-check, and the dispatch
 * routing for {@code triggerByName}.
 */
@ExtendWith(MockitoExtension.class)
class AdminLoaderServiceTest {

    @Mock private LoaderRunRepository runRepository;
    @Mock private GuestRouteCleanupJob cleanupJob;
    @Mock private EvStationLoader evStationLoader;
    @Mock private GeofabrikCoverageLoader coverageLoader;
    @Mock private ObjectProvider<GeofabrikCoverageLoader> coverageLoaderProvider;
    @Mock private PbfFileRepository pbfFileRepository;

    private AdminLoaderService service;

    @BeforeEach
    void setUp() {
        service = new AdminLoaderService(runRepository, cleanupJob, evStationLoader,
                coverageLoaderProvider, pbfFileRepository);
    }

    /** Convenience: build pbf_files mocks that yield the given pbf names. */
    private List<PbfFile> pbfRowsFor(String... names) {
        return java.util.Arrays.stream(names)
                .map(n -> {
                    PbfFile p = new PbfFile();
                    p.setPbfName(n);
                    return p;
                })
                .toList();
    }

    private LoaderRun runFor(String name, Status status) {
        LoaderRun r = new LoaderRun();
        r.setId(1L);
        r.setLoaderName(name);
        r.setStatus(status);
        r.setTriggerType(TriggerType.CRON);
        r.setStartedAt(ZonedDateTime.now().minusMinutes(2));
        if (status != Status.RUNNING) {
            r.setFinishedAt(ZonedDateTime.now().minusMinutes(1));
            r.setRowsAffected(42L);
        }
        return r;
    }

    @Test
    void listLoaders_includesStaticAndCoverageLoaders_inDeclarationOrder() {
        when(coverageLoaderProvider.getIfAvailable()).thenReturn(coverageLoader);
        when(pbfFileRepository.findAll()).thenReturn(pbfRowsFor("colorado", "nevada"));
        // Repository: distinct names returns nothing extra (no historical-only).
        when(runRepository.findDistinctLoaderNames()).thenReturn(List.of());
        // No last-run for any of them (empty).
        when(runRepository.findFirstByLoaderNameOrderByStartedAtDesc(any()))
                .thenReturn(Optional.empty());

        List<LoaderSummaryDto> list = service.listLoaders();

        // 3 static + 2 coverage pbfs = 5 total, in declaration order:
        // cleanup loaders → data loader → coverage pbfs.
        assertThat(list).extracting(LoaderSummaryDto::getName).containsExactly(
                GuestRouteCleanupJob.ROUTE_CLEANUP_LOADER_NAME,
                GuestRouteCleanupJob.EMAIL_TOKEN_CLEANUP_LOADER_NAME,
                EvStationLoader.LOADER_NAME,
                "ors-coverage:colorado",
                "ors-coverage:nevada");
        assertThat(list).extracting(LoaderSummaryDto::getCategory).containsExactly(
                "cleanup", "cleanup", "data", "coverage", "coverage");
    }

    @Test
    void listLoaders_includesHistoricalNames_atTheEnd() {
        when(coverageLoaderProvider.getIfAvailable()).thenReturn(null);   // local ORS off
        // texas was once configured; its rows linger in loader_runs even
        // though its pbf row was deleted.
        when(runRepository.findDistinctLoaderNames())
                .thenReturn(List.of("ors-coverage:texas"));
        when(runRepository.findFirstByLoaderNameOrderByStartedAtDesc(any()))
                .thenReturn(Optional.empty());

        List<LoaderSummaryDto> list = service.listLoaders();

        assertThat(list).extracting(LoaderSummaryDto::getName)
                .contains("ors-coverage:texas")
                .endsWith("ors-coverage:texas");
        // Historical-only ors-coverage: name still gets the "coverage" category.
        LoaderSummaryDto texas = list.stream()
                .filter(s -> "ors-coverage:texas".equals(s.getName()))
                .findFirst().orElseThrow();
        assertThat(texas.getCategory()).isEqualTo("coverage");
    }

    @Test
    void listLoaders_attachesLastRunSummary() {
        when(coverageLoaderProvider.getIfAvailable()).thenReturn(null);
        when(runRepository.findDistinctLoaderNames()).thenReturn(List.of());
        LoaderRun lastEv = runFor(EvStationLoader.LOADER_NAME, Status.SUCCESS);
        when(runRepository.findFirstByLoaderNameOrderByStartedAtDesc(EvStationLoader.LOADER_NAME))
                .thenReturn(Optional.of(lastEv));
        when(runRepository.findFirstByLoaderNameOrderByStartedAtDesc(GuestRouteCleanupJob.ROUTE_CLEANUP_LOADER_NAME))
                .thenReturn(Optional.empty());
        when(runRepository.findFirstByLoaderNameOrderByStartedAtDesc(GuestRouteCleanupJob.EMAIL_TOKEN_CLEANUP_LOADER_NAME))
                .thenReturn(Optional.empty());

        List<LoaderSummaryDto> list = service.listLoaders();

        LoaderSummaryDto evSummary = list.stream()
                .filter(s -> EvStationLoader.LOADER_NAME.equals(s.getName()))
                .findFirst().orElseThrow();
        assertThat(evSummary.getLastRun()).isNotNull();
        assertThat(evSummary.getLastRun().getStatus()).isEqualTo("SUCCESS");
        assertThat(evSummary.getLastRun().getRowsAffected()).isEqualTo(42L);

        LoaderSummaryDto cleanupSummary = list.stream()
                .filter(s -> GuestRouteCleanupJob.ROUTE_CLEANUP_LOADER_NAME.equals(s.getName()))
                .findFirst().orElseThrow();
        assertThat(cleanupSummary.getLastRun()).isNull();
    }

    @Test
    void history_returnsRequestedLimit() {
        Pageable expected = org.springframework.data.domain.PageRequest.of(0, 5);
        Page<LoaderRun> page = new PageImpl<>(List.of(
                runFor("ev-stations", Status.SUCCESS),
                runFor("ev-stations", Status.FAIL)));
        when(runRepository.findByLoaderNameOrderByStartedAtDesc(eq("ev-stations"), eq(expected)))
                .thenReturn(page);

        var rows = service.history("ev-stations", 5);

        assertThat(rows).hasSize(2);
    }

    @Test
    void history_clampsLimitsAtMaxAndDefault() {
        // limit <= 0 → DEFAULT_LIMIT (20)
        when(runRepository.findByLoaderNameOrderByStartedAtDesc(any(),
                eq(org.springframework.data.domain.PageRequest.of(0, 20))))
                .thenReturn(new PageImpl<>(List.of()));
        service.history("ev-stations", 0);
        verify(runRepository).findByLoaderNameOrderByStartedAtDesc(eq("ev-stations"),
                eq(org.springframework.data.domain.PageRequest.of(0, 20)));

        // limit > MAX → clamped to MAX (200)
        when(runRepository.findByLoaderNameOrderByStartedAtDesc(any(),
                eq(org.springframework.data.domain.PageRequest.of(0, 200))))
                .thenReturn(new PageImpl<>(List.of()));
        service.history("ev-stations", 9999);
        verify(runRepository).findByLoaderNameOrderByStartedAtDesc(eq("ev-stations"),
                eq(org.springframework.data.domain.PageRequest.of(0, 200)));
    }

    @Test
    void triggerByName_throwsConflictWhenRunningRowAlreadyExists() {
        when(runRepository.existsByLoaderNameAndStatus(
                GuestRouteCleanupJob.ROUTE_CLEANUP_LOADER_NAME, Status.RUNNING))
                .thenReturn(true);

        assertThatThrownBy(() -> service.triggerByName(
                GuestRouteCleanupJob.ROUTE_CLEANUP_LOADER_NAME))
                .isInstanceOf(LoaderRunRecorder.RunInProgressException.class);

        // Pre-check fired before any dispatch — neither the work runnable
        // nor a job method should have been invoked.
        verify(cleanupJob, never()).runRouteCleanup(any(TriggerType.class));
    }

    @Test
    void triggerByName_dispatchesRouteCleanup_offCallingThread() throws InterruptedException {
        when(runRepository.existsByLoaderNameAndStatus(
                GuestRouteCleanupJob.ROUTE_CLEANUP_LOADER_NAME, Status.RUNNING))
                .thenReturn(false);
        CountDownLatch invoked = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            invoked.countDown();
            return null;
        }).when(cleanupJob).runRouteCleanup(TriggerType.MANUAL);

        long callerThreadId = Thread.currentThread().getId();
        service.triggerByName(GuestRouteCleanupJob.ROUTE_CLEANUP_LOADER_NAME);

        assertThat(invoked.await(2, TimeUnit.SECONDS)).isTrue();
        verify(cleanupJob).runRouteCleanup(TriggerType.MANUAL);
        // Sanity — caller didn't accidentally run the work synchronously.
        assertThat(Thread.currentThread().getId()).isEqualTo(callerThreadId);
    }

    @Test
    void triggerByName_dispatchesEmailTokenCleanup() throws InterruptedException {
        when(runRepository.existsByLoaderNameAndStatus(
                GuestRouteCleanupJob.EMAIL_TOKEN_CLEANUP_LOADER_NAME, Status.RUNNING))
                .thenReturn(false);
        CountDownLatch invoked = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            invoked.countDown();
            return null;
        }).when(cleanupJob).runEmailTokenCleanup(TriggerType.MANUAL);

        service.triggerByName(GuestRouteCleanupJob.EMAIL_TOKEN_CLEANUP_LOADER_NAME);

        assertThat(invoked.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void triggerByName_dispatchesEvStations_withManualTrigger() throws InterruptedException {
        when(runRepository.existsByLoaderNameAndStatus(
                EvStationLoader.LOADER_NAME, Status.RUNNING))
                .thenReturn(false);
        CountDownLatch invoked = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            invoked.countDown();
            return null;
        }).when(evStationLoader).runWithRetryOnFailure(TriggerType.MANUAL);

        service.triggerByName(EvStationLoader.LOADER_NAME);

        assertThat(invoked.await(2, TimeUnit.SECONDS)).isTrue();
        verify(evStationLoader).runWithRetryOnFailure(TriggerType.MANUAL);
    }

    @Test
    void triggerByName_dispatchesCoverageRegion_withManualTrigger() throws InterruptedException {
        when(coverageLoaderProvider.getIfAvailable()).thenReturn(coverageLoader);
        when(pbfFileRepository.existsById("colorado")).thenReturn(true);
        when(runRepository.existsByLoaderNameAndStatus(
                "ors-coverage:colorado", Status.RUNNING)).thenReturn(false);
        CountDownLatch invoked = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            invoked.countDown();
            return null;
        }).when(coverageLoader).refresh(eq("colorado"), eq(TriggerType.MANUAL));

        service.triggerByName("ors-coverage:colorado");

        assertThat(invoked.await(2, TimeUnit.SECONDS)).isTrue();
        verify(coverageLoader).refresh("colorado", TriggerType.MANUAL);
    }

    @Test
    void triggerByName_unknownName_throwsIllegalArgument() {
        when(runRepository.existsByLoaderNameAndStatus("not-a-loader", Status.RUNNING))
                .thenReturn(false);

        assertThatThrownBy(() -> service.triggerByName("not-a-loader"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown loader");
    }

    @Test
    void triggerByName_coverage_whenLocalOrsDisabled_throwsIllegalState() {
        when(coverageLoaderProvider.getIfAvailable()).thenReturn(null);
        when(runRepository.existsByLoaderNameAndStatus(
                "ors-coverage:colorado", Status.RUNNING)).thenReturn(false);

        assertThatThrownBy(() -> service.triggerByName("ors-coverage:colorado"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Local ORS is not enabled");
    }

    @Test
    void triggerByName_coverage_unknownRegion_throwsIllegalArgument() {
        when(coverageLoaderProvider.getIfAvailable()).thenReturn(coverageLoader);
        when(pbfFileRepository.existsById("wyoming")).thenReturn(false);
        when(runRepository.existsByLoaderNameAndStatus(
                "ors-coverage:wyoming", Status.RUNNING)).thenReturn(false);

        assertThatThrownBy(() -> service.triggerByName("ors-coverage:wyoming"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in pbf_files");
    }

    @Test
    void refreshAllCoverageRegions_iteratesEveryRegion_inOrder() throws InterruptedException {
        when(coverageLoaderProvider.getIfAvailable()).thenReturn(coverageLoader);
        when(pbfFileRepository.findAll()).thenReturn(pbfRowsFor("colorado", "nevada", "utah"));

        // Each refresh counts down the latch when it fires; we wait for
        // all 3 to confirm sequential dispatch, then assert order.
        CountDownLatch allInvoked = new CountDownLatch(3);
        java.util.List<String> invocationOrder = java.util.Collections.synchronizedList(
                new java.util.ArrayList<>());
        org.mockito.Mockito.doAnswer(inv -> {
            invocationOrder.add(inv.getArgument(0));
            allInvoked.countDown();
            return null;
        }).when(coverageLoader).refresh(any(String.class), eq(TriggerType.MANUAL));

        List<String> enqueued = service.refreshAllCoverageRegions();
        assertThat(enqueued).containsExactly("colorado", "nevada", "utah");

        assertThat(allInvoked.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(invocationOrder).containsExactly("colorado", "nevada", "utah");
    }

    @Test
    void refreshAllCoverageRegions_continuesAfterPerRegionFailure() throws InterruptedException {
        when(coverageLoaderProvider.getIfAvailable()).thenReturn(coverageLoader);
        when(pbfFileRepository.findAll()).thenReturn(pbfRowsFor("colorado", "nevada"));
        // colorado throws; nevada must still be attempted.
        org.mockito.Mockito.doThrow(new RuntimeException("simulated"))
                .when(coverageLoader).refresh(eq("colorado"), eq(TriggerType.MANUAL));
        CountDownLatch nevadaInvoked = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            nevadaInvoked.countDown();
            return null;
        }).when(coverageLoader).refresh(eq("nevada"), eq(TriggerType.MANUAL));

        service.refreshAllCoverageRegions();

        assertThat(nevadaInvoked.await(2, TimeUnit.SECONDS))
                .as("colorado failure must not stop the loop")
                .isTrue();
    }

    @Test
    void refreshAllCoverageRegions_localOrsDisabled_throwsIllegalState() {
        when(coverageLoaderProvider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> service.refreshAllCoverageRegions())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Local ORS is not enabled");
    }
}
