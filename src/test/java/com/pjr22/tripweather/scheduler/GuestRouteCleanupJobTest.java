package com.pjr22.tripweather.scheduler;

import com.pjr22.tripweather.model.User;
import com.pjr22.tripweather.repository.EmailVerificationRepository;
import com.pjr22.tripweather.repository.PasswordResetRepository;
import com.pjr22.tripweather.repository.RouteRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the cleanup job. The actual SQL is exercised at runtime
 * against the configured database; here we verify wiring — that each sweep
 * resolves the right cutoff, calls the right repository, and respects the
 * stage-1-vs-stage-2 split that Phase 1 of ADMIN_CONSOLE.md introduced.
 */
@ExtendWith(MockitoExtension.class)
class GuestRouteCleanupJobTest {

    @Mock private RouteRepository routeRepository;
    @Mock private EmailVerificationRepository emailVerificationRepository;
    @Mock private PasswordResetRepository passwordResetRepository;
    @Mock private UserManagementService userManagementService;

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

    // -------------------- Two-stage route cleanup --------------------

    @Test
    void cleanGuestRoutes_runsBothStages_withRetentionAndGraceCutoffs() {
        User guest = newGuest();
        when(userManagementService.getOrCreateGuestUser()).thenReturn(guest);
        when(routeRepository.softDeleteGuestRoutesCreatedBefore(eq(guestId),
                any(ZonedDateTime.class), any(ZonedDateTime.class))).thenReturn(7);
        when(routeRepository.hardDeleteSoftDeletedBefore(any(ZonedDateTime.class)))
                .thenReturn(3);

        ZonedDateTime nowBefore = ZonedDateTime.now();
        job.cleanGuestRoutes();
        ZonedDateTime nowAfter = ZonedDateTime.now();

        // Stage 1: soft-delete cutoff is now - 30 days
        ArgumentCaptor<ZonedDateTime> softCutoff = ArgumentCaptor.forClass(ZonedDateTime.class);
        ArgumentCaptor<ZonedDateTime> softNow = ArgumentCaptor.forClass(ZonedDateTime.class);
        verify(routeRepository).softDeleteGuestRoutesCreatedBefore(
                eq(guestId), softCutoff.capture(), softNow.capture());
        assertThat(softCutoff.getValue())
                .isBetween(nowBefore.minusDays(30).minusSeconds(1),
                           nowAfter.minusDays(30).plusSeconds(1));
        assertThat(softNow.getValue()).isBetween(nowBefore.minusSeconds(1), nowAfter.plusSeconds(1));

        // Stage 2: hard-delete cutoff is now - 7 days
        ArgumentCaptor<ZonedDateTime> hardCutoff = ArgumentCaptor.forClass(ZonedDateTime.class);
        verify(routeRepository).hardDeleteSoftDeletedBefore(hardCutoff.capture());
        assertThat(hardCutoff.getValue())
                .isBetween(nowBefore.minusDays(7).minusSeconds(1),
                           nowAfter.minusDays(7).plusSeconds(1));
    }

    @Test
    void cleanGuestRoutes_disabledFlagSkipsStage1ButStillRunsStage2() {
        // Stage 2 (hard-delete past grace) must still run when guest cleanup
        // is disabled, otherwise an admin's manual soft-deletes would never
        // age out — the disable flag is meant to gate only the auto-aging
        // of guest routes.
        ReflectionTestUtils.setField(job, "guestRouteCleanupEnabled", false);
        when(routeRepository.hardDeleteSoftDeletedBefore(any(ZonedDateTime.class)))
                .thenReturn(2);

        job.cleanGuestRoutes();

        verifyNoInteractions(userManagementService);
        verify(routeRepository, never())
                .softDeleteGuestRoutesCreatedBefore(any(UUID.class),
                        any(ZonedDateTime.class), any(ZonedDateTime.class));
        verify(routeRepository).hardDeleteSoftDeletedBefore(any(ZonedDateTime.class));
    }

    @Test
    void cleanGuestRoutes_honoursCustomRetentionAndGraceDays() {
        ReflectionTestUtils.setField(job, "retentionDays", 90);
        ReflectionTestUtils.setField(job, "purgeGraceDays", 14);
        when(userManagementService.getOrCreateGuestUser()).thenReturn(newGuest());

        ZonedDateTime nowBefore = ZonedDateTime.now();
        job.cleanGuestRoutes();
        ZonedDateTime nowAfter = ZonedDateTime.now();

        ArgumentCaptor<ZonedDateTime> softCutoff = ArgumentCaptor.forClass(ZonedDateTime.class);
        verify(routeRepository).softDeleteGuestRoutesCreatedBefore(
                eq(guestId), softCutoff.capture(), any(ZonedDateTime.class));
        assertThat(softCutoff.getValue())
                .isBetween(nowBefore.minusDays(90).minusSeconds(1),
                           nowAfter.minusDays(90).plusSeconds(1));

        ArgumentCaptor<ZonedDateTime> hardCutoff = ArgumentCaptor.forClass(ZonedDateTime.class);
        verify(routeRepository).hardDeleteSoftDeletedBefore(hardCutoff.capture());
        assertThat(hardCutoff.getValue())
                .isBetween(nowBefore.minusDays(14).minusSeconds(1),
                           nowAfter.minusDays(14).plusSeconds(1));
    }

    // -------------------- Email-token cleanup --------------------

    @Test
    void cleanExpiredEmailTokens_deletesBothTablesUsingCurrentTime() {
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

        // Both cutoffs should be "approximately now" — captured between the
        // bookend timestamps. truncatedTo blunts nanosecond-precision flake.
        assertThat(verifCaptor.getValue())
                .isBetween(before.truncatedTo(ChronoUnit.MILLIS).minusSeconds(1),
                           after.plusSeconds(1));
        assertThat(resetCaptor.getValue())
                .isBetween(before.truncatedTo(ChronoUnit.MILLIS).minusSeconds(1),
                           after.plusSeconds(1));
    }

    @Test
    void cleanExpiredEmailTokens_runsEvenWhenGuestRouteCleanupDisabled() {
        // route.cleanup.enabled gates only the stage-1 guest-route sweep;
        // email-token cleanup is always-on.
        ReflectionTestUtils.setField(job, "guestRouteCleanupEnabled", false);

        job.cleanExpiredEmailTokens();

        verify(emailVerificationRepository).deleteByExpiresAtBefore(any(LocalDateTime.class));
        verify(passwordResetRepository).deleteByExpiresAtBefore(any(LocalDateTime.class));
    }

    @Test
    void cleanExpiredEmailTokens_independentFromGuestRouteCleanup() {
        // Tokens get cleaned without touching routes — useful sanity check
        // that the two methods don't accidentally short-circuit each other.
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
