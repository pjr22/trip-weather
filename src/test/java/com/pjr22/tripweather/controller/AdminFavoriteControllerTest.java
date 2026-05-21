package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.dto.AdminFavoritePage;
import com.pjr22.tripweather.dto.AdminFavoriteSummary;
import com.pjr22.tripweather.service.AdminFavoriteService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Authorisation (ROLE_ADMIN session) is enforced by the admin
 * SecurityFilterChain — see SecurityConfig — not the controller. These tests
 * cover the pass-through to {@link AdminFavoriteService} and the 404 mapping
 * for not-found / wrong-state ids. Mirrors {@link AdminRouteControllerTest}.
 *
 * <p>Phase 5 of FAVORITES_AND_ROUTE_MGMT.md.
 */
@ExtendWith(MockitoExtension.class)
class AdminFavoriteControllerTest {

    @Mock private AdminFavoriteService service;

    @InjectMocks
    private AdminFavoriteController controller;

    @Test
    void list_passesQueryParametersThroughToService() {
        AdminFavoritePage page = new AdminFavoritePage(
                List.of(new AdminFavoriteSummary(
                        UUID.randomUUID(), "Home", "1234 Elm St", 40.0, -105.0,
                        "alice@example.com", ZonedDateTime.now(), null)),
                1L, 1, 0, 25);
        when(service.list("home", "all", 0, 25, "label,asc")).thenReturn(page);

        AdminFavoritePage result = controller.list("home", "all", 0, 25, "label,asc");

        assertThat(result).isSameAs(page);
        verify(service).list("home", "all", 0, 25, "label,asc");
    }

    @Test
    void softDelete_returns204_whenServiceFlipsTheRow() {
        UUID id = UUID.randomUUID();
        when(service.softDelete(id)).thenReturn(true);

        ResponseEntity<Void> response = controller.softDelete(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void softDelete_throws404_whenServiceReportsNoChange() {
        UUID id = UUID.randomUUID();
        when(service.softDelete(id)).thenReturn(false);

        assertThatThrownBy(() -> controller.softDelete(id))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void restore_returns204_whenServiceClearsDeletedAt() {
        UUID id = UUID.randomUUID();
        when(service.restore(id)).thenReturn(true);

        ResponseEntity<Void> response = controller.restore(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void restore_throws404_whenServiceReportsNoChange() {
        UUID id = UUID.randomUUID();
        when(service.restore(id)).thenReturn(false);

        assertThatThrownBy(() -> controller.restore(id))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void hardDelete_returns204_whenServicePurges() {
        UUID id = UUID.randomUUID();
        when(service.hardDelete(id)).thenReturn(true);

        ResponseEntity<Void> response = controller.hardDelete(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void hardDelete_throws404_whenRowDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(service.hardDelete(id)).thenReturn(false);

        assertThatThrownBy(() -> controller.hardDelete(id))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
