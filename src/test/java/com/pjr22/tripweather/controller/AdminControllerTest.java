package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.routing.GeofabrikCoverageLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    private static final String TOKEN = "secret-token";

    @Mock private ObjectProvider<GeofabrikCoverageLoader> loaderProvider;
    @Mock private GeofabrikCoverageLoader loader;

    @Test
    void refuses_when_token_unconfigured() {
        AdminController controller = new AdminController(loaderProvider, "");

        ResponseEntity<Map<String, String>> response =
                controller.refreshCoverage("colorado", TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("error")).contains("admin token not configured");
        verify(loaderProvider, never()).getIfAvailable();
    }

    @Test
    void refuses_when_token_mismatched() {
        AdminController controller = new AdminController(loaderProvider, TOKEN);

        ResponseEntity<Map<String, String>> response =
                controller.refreshCoverage("colorado", "wrong");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("error")).isEqualTo("forbidden");
    }

    @Test
    void refuses_when_token_missing() {
        AdminController controller = new AdminController(loaderProvider, TOKEN);

        ResponseEntity<Map<String, String>> response =
                controller.refreshCoverage("colorado", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void returns_503_when_local_ors_disabled() {
        when(loaderProvider.getIfAvailable()).thenReturn(null);
        AdminController controller = new AdminController(loaderProvider, TOKEN);

        ResponseEntity<Map<String, String>> response =
                controller.refreshCoverage("colorado", TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().get("error")).contains("local ORS not enabled");
    }

    @Test
    void calls_loader_and_returns_200_on_success() {
        when(loaderProvider.getIfAvailable()).thenReturn(loader);
        AdminController controller = new AdminController(loaderProvider, TOKEN);

        ResponseEntity<Map<String, String>> response =
                controller.refreshCoverage("colorado", TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("refreshed");
        assertThat(response.getBody().get("region")).isEqualTo("colorado");
        verify(loader).refresh("colorado");
    }

    @Test
    void unknown_region_returns_404() {
        when(loaderProvider.getIfAvailable()).thenReturn(loader);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("Region 'wyoming' is not in trip.routing.local-regions"))
                .when(loader).refresh("wyoming");
        AdminController controller = new AdminController(loaderProvider, TOKEN);

        ResponseEntity<Map<String, String>> response =
                controller.refreshCoverage("wyoming", TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void loader_failure_returns_502() {
        when(loaderProvider.getIfAvailable()).thenReturn(loader);
        org.mockito.Mockito.doThrow(new RuntimeException("geofabrik 503"))
                .when(loader).refresh("colorado");
        AdminController controller = new AdminController(loaderProvider, TOKEN);

        ResponseEntity<Map<String, String>> response =
                controller.refreshCoverage("colorado", TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().get("error")).contains("geofabrik 503");
    }
}
