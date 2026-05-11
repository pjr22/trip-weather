package com.pjr22.tripweather.service;

import com.pjr22.tripweather.repository.RouteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.UUID;

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
 * (and by manual smoke testing); here we cover the soft-delete / restore
 * methods that delegate to the repository.
 *
 * <p>The Phase-1 placeholder {@code triggerCleanupAsync} moved to
 * {@link AdminLoaderService} in Phase 2; its tests now live in
 * {@link AdminLoaderServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class AdminRouteServiceTest {

    @Mock private RouteRepository routeRepository;
    @Mock private UserManagementService userManagementService;

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
