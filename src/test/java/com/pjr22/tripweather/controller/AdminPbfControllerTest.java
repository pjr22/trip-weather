package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.dto.PbfFileCreateRequest;
import com.pjr22.tripweather.dto.PbfFileDto;
import com.pjr22.tripweather.dto.PbfFileUpdateRequest;
import com.pjr22.tripweather.service.PbfFileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Authorization (ROLE_ADMIN session) is enforced by the admin
 * SecurityFilterChain, not here. These tests cover the controller's
 * exception → HTTP-status mapping and pass-through to {@link PbfFileService}.
 */
@ExtendWith(MockitoExtension.class)
class AdminPbfControllerTest {

    @Mock private PbfFileService service;

    @InjectMocks
    private AdminPbfController controller;

    private static PbfFileDto sampleDto(String name) {
        PbfFileDto dto = new PbfFileDto();
        dto.setPbfName(name);
        dto.setGeofabrikUrl("https://example/" + name + ".osm.pbf");
        dto.setActive(true);
        return dto;
    }

    @Test
    void list_passesThroughToService() {
        List<PbfFileDto> summaries = List.of(sampleDto("us-west"));
        when(service.listAll()).thenReturn(summaries);

        assertThat(controller.list()).isSameAs(summaries);
        verify(service).listAll();
    }

    @Test
    void get_returnsDto_whenPresent() {
        PbfFileDto dto = sampleDto("us-west");
        when(service.findOne("us-west")).thenReturn(Optional.of(dto));

        assertThat(controller.get("us-west")).isSameAs(dto);
    }

    @Test
    void get_404_whenAbsent() {
        when(service.findOne("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.get("missing"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void create_201_onSuccess() {
        PbfFileCreateRequest req = new PbfFileCreateRequest();
        req.setPbfName("us-west");
        req.setGeofabrikUrl("https://example/us-west.osm.pbf");
        PbfFileDto created = sampleDto("us-west");
        when(service.create(req)).thenReturn(created);

        assertThat(controller.create(req)).isSameAs(created);
    }

    @Test
    void create_409_whenServiceReportsAlreadyExists() {
        PbfFileCreateRequest req = new PbfFileCreateRequest();
        req.setPbfName("us-west");
        req.setGeofabrikUrl("https://example/us-west.osm.pbf");
        when(service.create(req)).thenThrow(
                new PbfFileService.PbfFileAlreadyExistsException("us-west"));

        assertThatThrownBy(() -> controller.create(req))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void update_404_whenAbsent() {
        when(service.update("missing", new PbfFileUpdateRequest()))
                .thenThrow(new PbfFileService.PbfFileNotFoundException("missing"));

        assertThatThrownBy(() -> controller.update("missing", new PbfFileUpdateRequest()))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void delete_204_onSuccess() {
        ResponseEntity<Void> response = controller.delete("us-west");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).delete("us-west");
    }

    @Test
    void delete_404_whenAbsent() {
        doThrow(new PbfFileService.PbfFileNotFoundException("missing"))
                .when(service).delete("missing");

        assertThatThrownBy(() -> controller.delete("missing"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void scheduleNow_passesThroughToService() {
        PbfFileDto dto = sampleDto("us-west");
        when(service.scheduleNow("us-west")).thenReturn(dto);

        assertThat(controller.scheduleNow("us-west")).isSameAs(dto);
    }

    @Test
    void checkMd5_502_whenUpstreamFails() {
        // Geofabrik down / unreachable / malformed response: upstream-is-broken
        // is the right shape for the client. 502 Bad Gateway.
        when(service.checkUpstreamNow("us-west")).thenThrow(
                new PbfFileService.PbfUpstreamCheckException("connection refused"));

        assertThatThrownBy(() -> controller.checkMd5("us-west"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void checkMd5_404_whenPbfAbsent() {
        when(service.checkUpstreamNow("missing")).thenThrow(
                new PbfFileService.PbfFileNotFoundException("missing"));

        assertThatThrownBy(() -> controller.checkMd5("missing"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void retryApply_passesThroughToService() {
        PbfFileDto dto = sampleDto("us-west");
        when(service.retryStuckApply("us-west")).thenReturn(dto);

        assertThat(controller.retryApply("us-west")).isSameAs(dto);
    }
}
