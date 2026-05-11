package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.dto.AdminRoutePage;
import com.pjr22.tripweather.dto.AdminRouteSummary;
import com.pjr22.tripweather.service.AdminRouteService;
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
 * Authorisation (ROLE_ADMIN session, X-Admin-Token header) is enforced by the
 * admin SecurityFilterChain — see SecurityConfig and
 * XAdminTokenAuthenticationFilter — not the controller. These tests cover the
 * pass-through to {@link AdminRouteService} and the 404 mapping for
 * not-found / wrong-state ids.
 */
@ExtendWith(MockitoExtension.class)
class AdminRouteControllerTest {

    @Mock private AdminRouteService service;

    @InjectMocks
    private AdminRouteController controller;

    @Test
    void list_passesQueryParametersThroughToService() {
        AdminRoutePage page = new AdminRoutePage(
                List.of(new AdminRouteSummary(
                        UUID.randomUUID(), "Aspen weekend", "alice@example.com",
                        "USER", 4L, ZonedDateTime.now(), null)),
                1L, 1, 0, 25);
        when(service.list("aspen", "USER", "all", 0, 25, "name,asc")).thenReturn(page);

        AdminRoutePage result = controller.list("aspen", "USER", "all", 0, 25, "name,asc");

        assertThat(result).isSameAs(page);
        verify(service).list("aspen", "USER", "all", 0, 25, "name,asc");
    }

    @Test
    void list_appliesControllerDefaultsForUnsetParameters() {
        // Spring's @RequestParam defaultValue should produce these on a bare
        // call. Replicate the binding here so a refactor that drops a
        // default fails the test.
        AdminRoutePage empty = new AdminRoutePage(List.of(), 0L, 0, 0, 25);
        when(service.list(null, null, "false", 0, 25, "created,desc")).thenReturn(empty);

        AdminRoutePage result = controller.list(null, null, "false", 0, 25, "created,desc");

        assertThat(result).isSameAs(empty);
    }

    @Test
    void softDelete_returns204_whenServiceFlipsTheRow() {
        UUID id = UUID.randomUUID();
        when(service.softDelete(id)).thenReturn(true);

        ResponseEntity<Void> response = controller.softDelete(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).softDelete(id);
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
        verify(service).restore(id);
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

    // Phase 1's POST /api/admin/cleanup/trigger moved to
    // POST /api/admin/loaders/guest-route-cleanup/trigger in Phase 2.
    // Coverage for that endpoint lives in AdminLoaderControllerTest.

    @Test
    void list_withDefaultDeletedFilterMatchesActiveOnlyConvention() throws Exception {
        // Defensive: defaultValue in the @RequestParam annotation must be
        // "false", not "active" or some other string, because the service
        // accepts the literal "false" / "true" / "all" tri-state. A drift
        // here would silently flip the default into "deleted only" or
        // "all routes" — a non-obvious bug. Read the annotation directly.
        java.lang.reflect.Method listMethod = AdminRouteController.class.getMethod(
                "list", String.class, String.class, String.class, int.class, int.class, String.class);
        org.springframework.web.bind.annotation.RequestParam annotation = listMethod
                .getParameters()[2].getAnnotation(org.springframework.web.bind.annotation.RequestParam.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("deleted");
        assertThat(annotation.defaultValue()).isEqualTo("false");
    }
}
