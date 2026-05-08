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
 * Unit tests for the cleanup job. The actual delete-by-cutoff queries are
 * exercised via JPA at runtime; here we verify wiring — that each sweep
 * resolves the right cutoff, calls the right repository, and (for the
 * guest-route sweep specifically) respects the enabled flag.
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
    }

    // -------------------- Guest route cleanup --------------------

    @Test
    void cleanGuestRoutes_deletesGuestRoutesOlderThanRetention() {
        User guest = newGuest();
        when(userManagementService.getOrCreateGuestUser()).thenReturn(guest);
        when(routeRepository.deleteByUserIdAndCreatedBefore(eq(guestId), any(ZonedDateTime.class)))
                .thenReturn(7);

        ZonedDateTime before = ZonedDateTime.now().minusDays(30);
        job.cleanGuestRoutes();
        ZonedDateTime after = ZonedDateTime.now().minusDays(30);

        ArgumentCaptor<ZonedDateTime> cutoffCaptor = ArgumentCaptor.forClass(ZonedDateTime.class);
        verify(routeRepository).deleteByUserIdAndCreatedBefore(eq(guestId), cutoffCaptor.capture());
        assertThat(cutoffCaptor.getValue()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
    }

    @Test
    void cleanGuestRoutes_disabledFlagSkipsAllWork() {
        ReflectionTestUtils.setField(job, "guestRouteCleanupEnabled", false);

        job.cleanGuestRoutes();

        verifyNoInteractions(userManagementService);
        verifyNoInteractions(routeRepository);
    }

    @Test
    void cleanGuestRoutes_honoursCustomRetentionDays() {
        ReflectionTestUtils.setField(job, "retentionDays", 7);
        when(userManagementService.getOrCreateGuestUser()).thenReturn(newGuest());

        ZonedDateTime before = ZonedDateTime.now().minusDays(7);
        job.cleanGuestRoutes();
        ZonedDateTime after = ZonedDateTime.now().minusDays(7);

        ArgumentCaptor<ZonedDateTime> cutoffCaptor = ArgumentCaptor.forClass(ZonedDateTime.class);
        verify(routeRepository).deleteByUserIdAndCreatedBefore(eq(guestId), cutoffCaptor.capture());
        assertThat(cutoffCaptor.getValue()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
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
        // route.cleanup.enabled gates only the guest-route sweep; email-token
        // cleanup is always-on.
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
        verify(routeRepository, never()).deleteByUserIdAndCreatedBefore(any(UUID.class), any(ZonedDateTime.class));
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
