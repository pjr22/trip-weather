package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.model.LoaderRun.TriggerType;
import com.pjr22.tripweather.routing.GeofabrikCoverageLoader;
import com.pjr22.tripweather.service.LoaderRunRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Authorization (X-Admin-Token header / ROLE_ADMIN session) is enforced by the
 * admin SecurityFilterChain — see SecurityConfig and XAdminTokenAuthenticationFilter
 * — not the controller. These tests cover the loader-dispatch behaviour the
 * controller is responsible for.
 *
 * <p>Phase 2 of ADMIN_CONSOLE.md: every refresh now records a loader_runs
 * row, so the controller passes {@link TriggerType#CRON} to {@code refresh}.
 * That recording happens inside the loader; the controller's contract is
 * just that it picks the right trigger type.
 */
@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock private ObjectProvider<GeofabrikCoverageLoader> loaderProvider;
    @Mock private GeofabrikCoverageLoader loader;

    @Test
    void returns_503_when_local_ors_disabled() {
        when(loaderProvider.getIfAvailable()).thenReturn(null);
        AdminController controller = new AdminController(loaderProvider);

        ResponseEntity<Map<String, String>> response =
                controller.refreshCoverage("colorado");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().get("error")).contains("local ORS not enabled");
    }

    @Test
    void calls_loader_and_returns_200_on_success() {
        when(loaderProvider.getIfAvailable()).thenReturn(loader);
        AdminController controller = new AdminController(loaderProvider);

        ResponseEntity<Map<String, String>> response =
                controller.refreshCoverage("colorado");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("refreshed");
        assertThat(response.getBody().get("region")).isEqualTo("colorado");
        // Production cron path → trigger=CRON, so the loader_runs entry
        // shows refreshes initiated by docker/refreshOrsGraph.sh.
        org.mockito.Mockito.verify(loader).refresh("colorado", TriggerType.CRON);
    }

    @Test
    void unknown_region_returns_404() {
        when(loaderProvider.getIfAvailable()).thenReturn(loader);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("Region 'wyoming' is not in trip.routing.local-regions"))
                .when(loader).refresh("wyoming", TriggerType.CRON);
        AdminController controller = new AdminController(loaderProvider);

        ResponseEntity<Map<String, String>> response =
                controller.refreshCoverage("wyoming");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void run_in_progress_returns_409() {
        // Phase 2: the legacy refresh-coverage endpoint shares the
        // concurrency guard with the admin console. If a manual refresh
        // is in flight when the docker cron fires, return 409 so cron
        // backs off; the next minute's tick will succeed.
        when(loaderProvider.getIfAvailable()).thenReturn(loader);
        org.mockito.Mockito.doThrow(new LoaderRunRecorder.RunInProgressException(
                        GeofabrikCoverageLoader.loaderName("colorado")))
                .when(loader).refresh("colorado", TriggerType.CRON);
        AdminController controller = new AdminController(loaderProvider);

        ResponseEntity<Map<String, String>> response =
                controller.refreshCoverage("colorado");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("error")).contains("already in progress");
    }

    @Test
    void loader_failure_returns_502() {
        when(loaderProvider.getIfAvailable()).thenReturn(loader);
        org.mockito.Mockito.doThrow(new RuntimeException("geofabrik 503"))
                .when(loader).refresh("colorado", TriggerType.CRON);
        AdminController controller = new AdminController(loaderProvider);

        ResponseEntity<Map<String, String>> response =
                controller.refreshCoverage("colorado");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().get("error")).contains("geofabrik 503");
    }
}
