package com.pjr22.tripweather.scheduler;

import com.pjr22.tripweather.model.LoaderRun;
import com.pjr22.tripweather.model.LoaderRun.TriggerType;
import com.pjr22.tripweather.model.User;
import com.pjr22.tripweather.repository.EmailVerificationRepository;
import com.pjr22.tripweather.repository.PasswordResetRepository;
import com.pjr22.tripweather.repository.RouteRepository;
import com.pjr22.tripweather.service.LoaderRunRecorder;
import com.pjr22.tripweather.service.UserManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the cleanup job. The actual SQL is exercised at runtime
 * against the configured database; here we verify wiring — that each sweep
 * resolves the right cutoff, calls the right repository, records the right
 * loader_runs entries (Phase 2 of ADMIN_CONSOLE.md), and respects the
 * stage-1-vs-stage-2 split (Phase 1).
 */
@ExtendWith(MockitoExtension.class)
class GuestRouteCleanupJobTest {

    @Mock private RouteRepository routeRepository;
    @Mock private EmailVerificationRepository emailVerificationRepository;
    @Mock private PasswordResetRepository passwordResetRepository;
    @Mock private UserManagementService userManagementService;
    @Mock private LoaderRunRecorder recorder;

    @InjectMocks
    private GuestRouteCleanupJob job;

    private UUID guestId;

    @BeforeEach
    void setUp() {
        guestId = UUID.randomUUID();
        // @Value-bound fields default to 0/false under @InjectMocks; set
        // them explicitly so the test exercises a realistic configuration.
        ReflectionTestUtils.setField(job, "guestRouteCleanupEnabled", true);
        ReflectionTestUtils.setField(job, "retentionDays", 30);
        ReflectionTestUtils.setField(job, "purgeGraceDays", 7);
    }

    private LoaderRun fakeRun(String name, TriggerType trigger) {
        LoaderRun run = new LoaderRun();
        run.setId(42L);
        run.setLoaderName(name);
        run.setTriggerType(trigger);
        run.setStatus(LoaderRun.Status.RUNNING);
        run.setStartedAt(ZonedDateTime.now());
        return run;
    }

    // -------------------- Two-stage route cleanup --------------------

    @Test
    void cleanGuestRoutes_runsBothStages_andRecordsSumOfRowsAffected() {
        when(recorder.start(eq(GuestRouteCleanupJob.ROUTE_CLEANUP_LOADER_NAME),
                eq(TriggerType.CRON)))
                .thenReturn(fakeRun(GuestRouteCleanupJob.ROUTE_CLEANUP_LOADER_NAME, TriggerType.CRON));
        User guest = newGuest();
        when(userManagementService.getOrCreateGuestUser()).thenReturn(guest);
        when(routeRepository.softDeleteGuestRoutesCreatedBefore(eq(guestId),
                any(ZonedDateTime.class), any(ZonedDateTime.class))).thenReturn(7);
        when(routeRepository.hardDeleteSoftDeletedBefore(any(ZonedDateTime.class)))
                .thenReturn(3);

        ZonedDateTime nowBefore = ZonedDateTime.now();
        job.cleanGuestRoutes();
        ZonedDateTime nowAfter = ZonedDateTime.now();

        // Stage 1 cutoff = now - 30d, with the same `now` threaded through
        // as deletedAt for newly soft-deleted rows.
        ArgumentCaptor<ZonedDateTime> softCutoff = ArgumentCaptor.forClass(ZonedDateTime.class);
        ArgumentCaptor<ZonedDateTime> softNow = ArgumentCaptor.forClass(ZonedDateTime.class);
        verify(routeRepository).softDeleteGuestRoutesCreatedBefore(
                eq(guestId), softCutoff.capture(), softNow.capture());
        assertThat(softCutoff.getValue())
                .isBetween(nowBefore.minusDays(30).minusSeconds(1),
                           nowAfter.minusDays(30).plusSeconds(1));
        assertThat(softNow.getValue()).isBetween(nowBefore.minusSeconds(1), nowAfter.plusSeconds(1));

        // Stage 2 cutoff = now - 7d.
        ArgumentCaptor<ZonedDateTime> hardCutoff = ArgumentCaptor.forClass(ZonedDateTime.class);
        verify(routeRepository).hardDeleteSoftDeletedBefore(hardCutoff.capture());
        assertThat(hardCutoff.getValue())
                .isBetween(nowBefore.minusDays(7).minusSeconds(1),
                           nowAfter.minusDays(7).plusSeconds(1));

        // Recorder gets soft+hard summed as rowsAffected.
        ArgumentCaptor<Long> rows = ArgumentCaptor.forClass(Long.class);
        verify(recorder).success(any(LoaderRun.class), rows.capture());
        assertThat(rows.getValue()).isEqualTo(10L);
    }

    @Test
    void cleanGuestRoutes_disabledFlagSkipsStage1ButStillRunsStage2() {
        // Stage 2 (hard-delete past grace) must still run when guest cleanup
        // is disabled, otherwise an admin's manual soft-deletes would never
        // age out — the disable flag is meant to gate only the auto-aging
        // of guest routes.
        when(recorder.start(eq(GuestRouteCleanupJob.ROUTE_CLEANUP_LOADER_NAME),
                eq(TriggerType.CRON)))
                .thenReturn(fakeRun(GuestRouteCleanupJob.ROUTE_CLEANUP_LOADER_NAME, TriggerType.CRON));
        ReflectionTestUtils.setField(job, "guestRouteCleanupEnabled", false);
        when(routeRepository.hardDeleteSoftDeletedBefore(any(ZonedDateTime.class)))
                .thenReturn(2);

        job.cleanGuestRoutes();

        verifyNoInteractions(userManagementService);
        verify(routeRepository, never())
                .softDeleteGuestRoutesCreatedBefore(any(UUID.class),
                        any(ZonedDateTime.class), any(ZonedDateTime.class));
        verify(routeRepository).hardDeleteSoftDeletedBefore(any(ZonedDateTime.class));
        verify(recorder).success(any(LoaderRun.class), eq(2L));
    }

    @Test
    void cleanGuestRoutes_runInProgressOnCron_logsAndSkipsWithoutThrowing() {
        // Recorder reports another run in flight on a CRON tick. The cron
        // entry point must catch and log-skip, not propagate.
        when(recorder.start(eq(GuestRouteCleanupJob.ROUTE_CLEANUP_LOADER_NAME),
                eq(TriggerType.CRON)))
                .thenThrow(new LoaderRunRecorder.RunInProgressException(
                        GuestRouteCleanupJob.ROUTE_CLEANUP_LOADER_NAME));

        job.cleanGuestRoutes();

        verifyNoInteractions(userManagementService);
        verify(routeRepository, never())
                .softDeleteGuestRoutesCreatedBefore(any(UUID.class),
                        any(ZonedDateTime.class), any(ZonedDateTime.class));
        verify(routeRepository, never()).hardDeleteSoftDeletedBefore(any(ZonedDateTime.class));
    }

    @Test
    void runRouteCleanup_runInProgressOnManual_propagates() {
        // On a MANUAL trigger, the conflict must propagate so the
        // controller can map it to HTTP 409.
        when(recorder.start(eq(GuestRouteCleanupJob.ROUTE_CLEANUP_LOADER_NAME),
                eq(TriggerType.MANUAL)))
                .thenThrow(new LoaderRunRecorder.RunInProgressException(
                        GuestRouteCleanupJob.ROUTE_CLEANUP_LOADER_NAME));

        assertThatThrownBy(() -> job.runRouteCleanup(TriggerType.MANUAL))
                .isInstanceOf(LoaderRunRecorder.RunInProgressException.class);
    }

    @Test
    void runRouteCleanup_failurePath_recordsFailAndDoesNotThrowOnCron() {
        // A CRON-path failure should still be recorded, but the cron
        // method shouldn't propagate so a misbehaving cleanup doesn't
        // poison Spring's scheduler thread.
        when(recorder.start(eq(GuestRouteCleanupJob.ROUTE_CLEANUP_LOADER_NAME),
                eq(TriggerType.CRON)))
                .thenReturn(fakeRun(GuestRouteCleanupJob.ROUTE_CLEANUP_LOADER_NAME, TriggerType.CRON));
        when(userManagementService.getOrCreateGuestUser())
                .thenThrow(new RuntimeException("simulated DB outage"));

        job.cleanGuestRoutes();   // must not throw

        verify(recorder).fail(any(LoaderRun.class), any(Throwable.class));
        verify(recorder, never()).success(any(LoaderRun.class), anyLong());
    }

    // -------------------- Email-token cleanup --------------------

    @Test
    void cleanExpiredEmailTokens_deletesBothTablesAndRecordsCount() {
        when(recorder.start(eq(GuestRouteCleanupJob.EMAIL_TOKEN_CLEANUP_LOADER_NAME),
                eq(TriggerType.CRON)))
                .thenReturn(fakeRun(GuestRouteCleanupJob.EMAIL_TOKEN_CLEANUP_LOADER_NAME,
                        TriggerType.CRON));
        when(emailVerificationRepository.deleteByExpiresAtBefore(any(LocalDateTime.class)))
                .thenReturn(3);
        when(passwordResetRepository.deleteByExpiresAtBefore(any(LocalDateTime.class)))
                .thenReturn(5);

        LocalDateTime before = LocalDateTime.now();
        job.cleanExpiredEmailTokens();
        LocalDateTime after = LocalDateTime.now();

        ArgumentCaptor<LocalDateTime> verifCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(emailVerificationRepository).deleteByExpiresAtBefore(verifCaptor.capture());
        ArgumentCaptor<LocalDateTime> resetCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(passwordResetRepository).deleteByExpiresAtBefore(resetCaptor.capture());

        assertThat(verifCaptor.getValue())
                .isBetween(before.truncatedTo(ChronoUnit.MILLIS).minusSeconds(1),
                           after.plusSeconds(1));
        assertThat(resetCaptor.getValue())
                .isBetween(before.truncatedTo(ChronoUnit.MILLIS).minusSeconds(1),
                           after.plusSeconds(1));

        verify(recorder).success(any(LoaderRun.class), eq(8L));
    }

    @Test
    void cleanExpiredEmailTokens_runsEvenWhenGuestRouteCleanupDisabled() {
        // route.cleanup.enabled gates only the stage-1 guest-route sweep;
        // email-token cleanup is always-on.
        ReflectionTestUtils.setField(job, "guestRouteCleanupEnabled", false);
        when(recorder.start(eq(GuestRouteCleanupJob.EMAIL_TOKEN_CLEANUP_LOADER_NAME),
                eq(TriggerType.CRON)))
                .thenReturn(fakeRun(GuestRouteCleanupJob.EMAIL_TOKEN_CLEANUP_LOADER_NAME,
                        TriggerType.CRON));

        job.cleanExpiredEmailTokens();

        verify(emailVerificationRepository).deleteByExpiresAtBefore(any(LocalDateTime.class));
        verify(passwordResetRepository).deleteByExpiresAtBefore(any(LocalDateTime.class));
    }

    @Test
    void cleanExpiredEmailTokens_independentFromGuestRouteCleanup() {
        // Tokens get cleaned without touching routes — useful sanity check
        // that the two methods don't accidentally short-circuit each other.
        when(recorder.start(eq(GuestRouteCleanupJob.EMAIL_TOKEN_CLEANUP_LOADER_NAME),
                eq(TriggerType.CRON)))
                .thenReturn(fakeRun(GuestRouteCleanupJob.EMAIL_TOKEN_CLEANUP_LOADER_NAME,
                        TriggerType.CRON));

        job.cleanExpiredEmailTokens();

        verify(emailVerificationRepository).deleteByExpiresAtBefore(any(LocalDateTime.class));
        verify(passwordResetRepository).deleteByExpiresAtBefore(any(LocalDateTime.class));
        verify(routeRepository, never()).softDeleteGuestRoutesCreatedBefore(
                any(UUID.class), any(ZonedDateTime.class), any(ZonedDateTime.class));
        verify(routeRepository, never()).hardDeleteSoftDeletedBefore(any(ZonedDateTime.class));
    }

    private User newGuest() {
        User guest = new User();
        guest.setId(guestId);
        guest.setName("guest");
        guest.setEmail("guest@local");
        guest.setEnabled(true);
        return guest;
    }
}
