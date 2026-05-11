package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.dto.LoaderRunDto;
import com.pjr22.tripweather.dto.LoaderSummaryDto;
import com.pjr22.tripweather.service.AdminLoaderService;
import com.pjr22.tripweather.service.LoaderRunRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Authorisation (ROLE_ADMIN session) is enforced by the admin
 * SecurityFilterChain. These tests cover the controller's pass-through to
 * {@link AdminLoaderService} and the exception → HTTP-status mapping.
 */
@ExtendWith(MockitoExtension.class)
class AdminLoaderControllerTest {

    @Mock private AdminLoaderService service;

    @InjectMocks
    private AdminLoaderController controller;

    @Test
    void list_passesThroughToService() {
        List<LoaderSummaryDto> summaries = List.of(
                new LoaderSummaryDto("ev-stations", "data", null));
        when(service.listLoaders()).thenReturn(summaries);

        assertThat(controller.list()).isSameAs(summaries);
        verify(service).listLoaders();
    }

    @Test
    void runs_passesNameAndLimit() {
        List<LoaderRunDto> rows = List.of(new LoaderRunDto());
        when(service.history("ev-stations", 7)).thenReturn(rows);

        assertThat(controller.runs("ev-stations", 7)).isSameAs(rows);
        verify(service).history("ev-stations", 7);
    }

    @Test
    void runs_defaultLimitIs20() {
        when(service.history(anyString(), anyInt())).thenReturn(List.of());

        controller.runs("ev-stations", 20);   // simulate Spring's defaultValue
        verify(service).history("ev-stations", 20);
    }

    @Test
    void trigger_returns202_whenServiceAccepts() {
        // Service void-returns; success path means just 202.
        ResponseEntity<Void> response = controller.trigger("ev-stations");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(service).triggerByName("ev-stations");
    }

    @Test
    void trigger_409_whenAnotherRunAlreadyInProgress() {
        doThrow(new LoaderRunRecorder.RunInProgressException("ev-stations"))
                .when(service).triggerByName("ev-stations");

        assertThatThrownBy(() -> controller.trigger("ev-stations"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void trigger_404_whenLoaderNameUnknown() {
        doThrow(new IllegalArgumentException("Unknown loader: not-a-loader"))
                .when(service).triggerByName("not-a-loader");

        assertThatThrownBy(() -> controller.trigger("not-a-loader"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void trigger_503_whenCoverageRequestedButLocalOrsDisabled() {
        doThrow(new IllegalStateException(
                "Local ORS is not enabled (trip.local.ors.enabled=false); "
              + "coverage loader is unavailable."))
                .when(service).triggerByName("ors-coverage:colorado");

        assertThatThrownBy(() -> controller.trigger("ors-coverage:colorado"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void triggerCoverageRegion_buildsLoaderName_andDelegatesToTrigger() {
        // Convenience URL — controller prefixes "ors-coverage:" before
        // calling the same triggerByName path.
        ResponseEntity<Void> response = controller.triggerCoverageRegion("colorado");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(service).triggerByName("ors-coverage:colorado");
    }

    @Test
    void refreshAllCoverage_returns202_withRegionList() {
        when(service.refreshAllCoverageRegions())
                .thenReturn(List.of("colorado", "nevada"));

        ResponseEntity<Map<String, Object>> response = controller.refreshAllCoverage();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("regions"))
                .isEqualTo(List.of("colorado", "nevada"));
        assertThat(response.getBody().get("count")).isEqualTo(2);
    }

    @Test
    void refreshAllCoverage_503_whenLocalOrsDisabled() {
        doThrow(new IllegalStateException(
                "Local ORS is not enabled (trip.local.ors.enabled=false); "
              + "coverage loader is unavailable."))
                .when(service).refreshAllCoverageRegions();

        assertThatThrownBy(() -> controller.refreshAllCoverage())
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
