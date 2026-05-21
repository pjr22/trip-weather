package com.pjr22.tripweather.service;

import com.pjr22.tripweather.repository.FavoriteWaypointRepository;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminFavoriteService}. Mirrors
 * {@link AdminRouteServiceTest}: the {@code list(...)} method goes through
 * {@link jakarta.persistence.EntityManager} for native SQL — that code path
 * is exercised by integration tests against a live PostgreSQL (and by manual
 * smoke testing); here we cover the soft-delete / restore / hard-delete
 * methods that delegate to the repository.
 *
 * <p>Phase 5 of FAVORITES_AND_ROUTE_MGMT.md.
 */
@ExtendWith(MockitoExtension.class)
class AdminFavoriteServiceTest {

    @Mock private FavoriteWaypointRepository favoriteRepository;

    @InjectMocks
    private AdminFavoriteService service;

    @Test
    void softDelete_returnsTrueWhenRepositoryUpdatesARow() {
        UUID id = UUID.randomUUID();
        when(favoriteRepository.adminSoftDelete(eq(id), any(ZonedDateTime.class))).thenReturn(1);

        ZonedDateTime before = ZonedDateTime.now();
        boolean result = service.softDelete(id);
        ZonedDateTime after = ZonedDateTime.now();

        assertThat(result).isTrue();
        ArgumentCaptor<ZonedDateTime> nowCaptor = ArgumentCaptor.forClass(ZonedDateTime.class);
        verify(favoriteRepository).adminSoftDelete(eq(id), nowCaptor.capture());
        // The "now" we wrote should be approximately the current time.
        assertThat(nowCaptor.getValue()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
    }

    @Test
    void softDelete_returnsFalseWhenRepositoryUpdatesNothing() {
        UUID id = UUID.randomUUID();
        when(favoriteRepository.adminSoftDelete(eq(id), any(ZonedDateTime.class))).thenReturn(0);

        assertThat(service.softDelete(id)).isFalse();
    }

    @Test
    void restore_returnsTrueWhenRepositoryUpdatesARow() {
        UUID id = UUID.randomUUID();
        when(favoriteRepository.adminRestore(id)).thenReturn(1);

        assertThat(service.restore(id)).isTrue();
        verify(favoriteRepository).adminRestore(id);
    }

    @Test
    void restore_returnsFalseWhenRepositoryUpdatesNothing() {
        UUID id = UUID.randomUUID();
        when(favoriteRepository.adminRestore(id)).thenReturn(0);

        assertThat(service.restore(id)).isFalse();
    }

    @Test
    void hardDelete_returnsTrueWhenRepositoryDeletesARow() {
        UUID id = UUID.randomUUID();
        when(favoriteRepository.adminHardDelete(id)).thenReturn(1);

        assertThat(service.hardDelete(id)).isTrue();
        verify(favoriteRepository).adminHardDelete(id);
    }

    @Test
    void hardDelete_returnsFalseWhenRepositoryDeletesNothing() {
        UUID id = UUID.randomUUID();
        when(favoriteRepository.adminHardDelete(id)).thenReturn(0);

        assertThat(service.hardDelete(id)).isFalse();
    }
}
