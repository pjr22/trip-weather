package com.pjr22.tripweather.service;

import com.pjr22.tripweather.repository.RouteRepository;
import com.pjr22.tripweather.scheduler.GuestRouteCleanupJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminRouteService}. The {@code list(...)} method goes
 * through {@link jakarta.persistence.EntityManager} for native SQL — that
 * code path is exercised by integration tests against a live PostgreSQL
 * (and by manual smoke testing); here we cover the methods that delegate
 * to the repository and the async cleanup trigger.
 */
@ExtendWith(MockitoExtension.class)
class AdminRouteServiceTest {

    @Mock private RouteRepository routeRepository;
    @Mock private UserManagementService userManagementService;
    @Mock private GuestRouteCleanupJob cleanupJob;

    @InjectMocks
    private AdminRouteService service;

    @Test
    void softDelete_returnsTrueWhenRepositoryUpdatesARow() {
        UUID id = UUID.randomUUID();
        when(routeRepository.adminSoftDelete(eq(id), any(ZonedDateTime.class))).thenReturn(1);

        ZonedDateTime before = ZonedDateTime.now();
        boolean result = service.softDelete(id);
        ZonedDateTime after = ZonedDateTime.now();

        assertThat(result).isTrue();
        ArgumentCaptor<ZonedDateTime> nowCaptor = ArgumentCaptor.forClass(ZonedDateTime.class);
        verify(routeRepository).adminSoftDelete(eq(id), nowCaptor.capture());
        // The "now" we wrote should be approximately the current time.
        assertThat(nowCaptor.getValue()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
    }

    @Test
    void softDelete_returnsFalseWhenRepositoryUpdatesNothing() {
        UUID id = UUID.randomUUID();
        when(routeRepository.adminSoftDelete(eq(id), any(ZonedDateTime.class))).thenReturn(0);

        assertThat(service.softDelete(id)).isFalse();
    }

    @Test
    void restore_returnsTrueWhenRepositoryUpdatesARow() {
        UUID id = UUID.randomUUID();
        when(routeRepository.adminRestore(id)).thenReturn(1);

        assertThat(service.restore(id)).isTrue();
        verify(routeRepository).adminRestore(id);
    }

    @Test
    void restore_returnsFalseWhenRepositoryUpdatesNothing() {
        UUID id = UUID.randomUUID();
        when(routeRepository.adminRestore(id)).thenReturn(0);

        assertThat(service.restore(id)).isFalse();
    }

    @Test
    void triggerCleanupAsync_invokesCleanGuestRoutes_offTheCallingThread()
            throws InterruptedException {
        // CountDownLatch is the deterministic primitive: the stub counts
        // it down when the cleanup runs; the test awaits it with a timeout.
        // No polling, no awaitility dependency.
        CountDownLatch invoked = new CountDownLatch(1);
        AtomicLong invokingThreadId = new AtomicLong(-1L);
        long callerThreadId = Thread.currentThread().threadId();
        org.mockito.Mockito.doAnswer(invocation -> {
            invokingThreadId.set(Thread.currentThread().threadId());
            invoked.countDown();
            return null;
        }).when(cleanupJob).cleanGuestRoutes();

        service.triggerCleanupAsync();

        assertThat(invoked.await(2, TimeUnit.SECONDS))
                .as("cleanup should run within 2 s of the trigger")
                .isTrue();
        verify(cleanupJob).cleanGuestRoutes();
        assertThat(invokingThreadId.get())
                .as("cleanup must run on a different thread than the caller")
                .isNotEqualTo(callerThreadId);
    }

    @Test
    void triggerCleanupAsync_swallowsCleanupExceptions()
            throws InterruptedException {
        // The async cleanup must not propagate exceptions to the caller —
        // the controller has already returned 202 and there's no client to
        // surface the error to. Verify the trigger call itself doesn't
        // throw on the test thread.
        CountDownLatch invoked = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            invoked.countDown();
            throw new RuntimeException("simulated cleanup failure");
        }).when(cleanupJob).cleanGuestRoutes();

        service.triggerCleanupAsync(); // must not throw on this thread
        assertThat(invoked.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void neitherSoftDeleteNorRestoreTouchesUserManagement() {
        // The service should never resolve users for these single-route
        // operations — they're addressed by UUID directly. A regression
        // here would suggest someone accidentally introduced an ownership
        // check that doesn't belong on the admin path.
        UUID id = UUID.randomUUID();
        when(routeRepository.adminSoftDelete(eq(id), any(ZonedDateTime.class))).thenReturn(1);
        when(routeRepository.adminRestore(id)).thenReturn(1);

        service.softDelete(id);
        service.restore(id);

        verify(userManagementService, never()).getOrCreateGuestUser();
        verify(userManagementService, never()).findUserById(any(UUID.class));
        verify(userManagementService, never()).getUserByIdOrGuest(any(UUID.class));
    }
}
