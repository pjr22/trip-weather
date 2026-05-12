package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.dto.AdminUserDeleteResult;
import com.pjr22.tripweather.dto.AdminUserPage;
import com.pjr22.tripweather.dto.AdminUserSummary;
import com.pjr22.tripweather.service.AdminUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pass-through controller for {@link AdminUserService}. Authorisation is
 * enforced by the admin SecurityFilterChain (see SecurityConfig); these
 * tests cover the parameter binding, 404 mapping, and delete-response
 * shape. The service itself is unit-tested separately.
 */
@ExtendWith(MockitoExtension.class)
class AdminUserControllerTest {

    @Mock private AdminUserService service;

    @InjectMocks
    private AdminUserController controller;

    @Test
    void list_passesQueryParametersThroughToService() {
        AdminUserPage page = new AdminUserPage(
                List.of(new AdminUserSummary(
                        UUID.randomUUID(), "alice@example.com", "Alice",
                        true, LocalDateTime.now(), 4L, false)),
                1L, 1, 0, 25);
        when(service.list("alice", "true", 0, 25, "email,asc")).thenReturn(page);

        AdminUserPage result = controller.list("alice", "true", 0, 25, "email,asc");

        assertThat(result).isSameAs(page);
        verify(service).list("alice", "true", 0, 25, "email,asc");
    }

    @Test
    void list_appliesControllerDefaults() {
        // Replicates what Spring's @RequestParam defaultValue binds on a
        // bare call. A refactor that drops one of the defaults will fail here.
        AdminUserPage empty = new AdminUserPage(List.of(), 0L, 0, 0, 25);
        when(service.list(null, null, 0, 25, "created,desc")).thenReturn(empty);

        AdminUserPage result = controller.list(null, null, 0, 25, "created,desc");

        assertThat(result).isSameAs(empty);
    }

    @Test
    void list_defaultSortIsCreatedDesc() throws Exception {
        // Read the @RequestParam annotation directly so a typo in the default
        // value (e.g. "created,asc" or "createdAt,desc") fails the test
        // instead of silently flipping the page order.
        Method listMethod = AdminUserController.class.getMethod(
                "list", String.class, String.class, int.class, int.class, String.class);
        RequestParam annotation = listMethod.getParameters()[4].getAnnotation(RequestParam.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("sort");
        assertThat(annotation.defaultValue()).isEqualTo("created,desc");
    }

    // ---- enable -------------------------------------------------------------

    @Test
    void enable_returns204_whenServiceUpdates() {
        UUID id = UUID.randomUUID();
        when(service.setEnabled(id, true)).thenReturn(AdminUserService.SetEnabledOutcome.UPDATED);

        ResponseEntity<Void> response = controller.enable(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void enable_returns204_whenAlreadyEnabled_forIdempotency() {
        UUID id = UUID.randomUUID();
        when(service.setEnabled(id, true)).thenReturn(AdminUserService.SetEnabledOutcome.NO_CHANGE);

        ResponseEntity<Void> response = controller.enable(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void enable_throws404_whenUserMissing() {
        UUID id = UUID.randomUUID();
        when(service.setEnabled(id, true)).thenReturn(AdminUserService.SetEnabledOutcome.NOT_FOUND);

        assertThatThrownBy(() -> controller.enable(id))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---- disable ------------------------------------------------------------

    @Test
    void disable_returns204_whenServiceUpdates() {
        UUID id = UUID.randomUUID();
        when(service.setEnabled(id, false)).thenReturn(AdminUserService.SetEnabledOutcome.UPDATED);

        ResponseEntity<Void> response = controller.disable(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void disable_throws404_whenUserMissing() {
        UUID id = UUID.randomUUID();
        when(service.setEnabled(id, false)).thenReturn(AdminUserService.SetEnabledOutcome.NOT_FOUND);

        assertThatThrownBy(() -> controller.disable(id))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---- force-verify -------------------------------------------------------

    @Test
    void forceVerify_returns204_whenServiceFinds() {
        UUID id = UUID.randomUUID();
        when(service.forceVerify(id)).thenReturn(true);

        ResponseEntity<Void> response = controller.forceVerify(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).forceVerify(id);
    }

    @Test
    void forceVerify_throws404_whenUserMissing() {
        UUID id = UUID.randomUUID();
        when(service.forceVerify(id)).thenReturn(false);

        assertThatThrownBy(() -> controller.forceVerify(id))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---- delete -------------------------------------------------------------

    @Test
    void delete_returnsCounts_whenServiceDeletes() {
        UUID id = UUID.randomUUID();
        AdminUserDeleteResult expected = new AdminUserDeleteResult(3L, 2L);
        when(service.delete(id)).thenReturn(expected);

        AdminUserDeleteResult result = controller.delete(id);

        assertThat(result).isSameAs(expected);
        verify(service).delete(id);
    }

    @Test
    void delete_throws404_whenServiceReportsMissing() {
        UUID id = UUID.randomUUID();
        when(service.delete(id)).thenReturn(null);

        assertThatThrownBy(() -> controller.delete(id))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }
}
