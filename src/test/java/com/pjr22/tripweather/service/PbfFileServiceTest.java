package com.pjr22.tripweather.service;

import com.pjr22.tripweather.dto.PbfFileCreateRequest;
import com.pjr22.tripweather.dto.PbfFileDto;
import com.pjr22.tripweather.dto.PbfFileUpdateRequest;
import com.pjr22.tripweather.model.PbfFile;
import com.pjr22.tripweather.model.RoutingCoverage;
import com.pjr22.tripweather.repository.PbfFileRepository;
import com.pjr22.tripweather.repository.RoutingCoverageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the CRUD + retry / schedule entry points of
 * {@link PbfFileService}. The {@code checkUpstreamNow} path goes out to
 * Geofabrik over HTTP; that's exercised by manual smoke testing and
 * documented behaviour rather than mocked here, because the value of the
 * test would be largely tautological with the implementation.
 */
@ExtendWith(MockitoExtension.class)
class PbfFileServiceTest {

    @Mock private PbfFileRepository repository;
    @Mock private RoutingCoverageRepository routingRepository;

    @InjectMocks
    private PbfFileService service;

    private static PbfFile sample(String name) {
        PbfFile p = new PbfFile();
        p.setPbfName(name);
        p.setGeofabrikUrl("https://download.geofabrik.de/north-america/us/" + name + "-latest.osm.pbf");
        p.setActive(true);
        p.setCheckIntervalDays(7);
        return p;
    }

    @Test
    void listAll_sortsByName_andAnnotatesFreshnessFlags() {
        PbfFile a = sample("colorado");
        a.setLastApplyMd5("aaaa");
        a.setLastRemoteMd5("aaaa");   // same md5 → not stale
        PbfFile b = sample("nevada");
        b.setLastApplyMd5("bbbb");
        b.setLastRemoteMd5("cccc");   // different → stale
        // Repository returns out-of-order; service sorts.
        when(repository.findAll()).thenReturn(List.of(b, a));
        when(routingRepository.findAll()).thenReturn(List.of());

        List<PbfFileDto> out = service.listAll();

        assertThat(out).extracting(PbfFileDto::getPbfName)
                .containsExactly("colorado", "nevada");
        assertThat(out.get(0).isStale()).isFalse();
        assertThat(out.get(1).isStale()).isTrue();
    }

    @Test
    void create_inserts_andReturnsDto() {
        PbfFileCreateRequest req = new PbfFileCreateRequest();
        req.setPbfName("us-west");
        req.setGeofabrikUrl("https://download.geofabrik.de/north-america/us-west-latest.osm.pbf");
        when(repository.existsById("us-west")).thenReturn(false);
        when(repository.save(any(PbfFile.class))).thenAnswer(inv -> inv.getArgument(0));
        // Phase 2c: paired row gets pulled back after create() to populate
        // the DTO. Stub a minimal RoutingCoverage so the read returns.
        RoutingCoverage rc = new RoutingCoverage();
        rc.setName("us-west");
        rc.setEnabled(true);
        when(routingRepository.findById("us-west")).thenReturn(Optional.of(rc));

        PbfFileDto dto = service.create(req);

        ArgumentCaptor<PbfFile> savedCaptor = ArgumentCaptor.forClass(PbfFile.class);
        verify(repository).save(savedCaptor.capture());
        PbfFile saved = savedCaptor.getValue();
        assertThat(saved.getPbfName()).isEqualTo("us-west");
        // Defaults applied when request omits the corresponding field.
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getCheckIntervalDays()).isEqualTo(7);
        assertThat(saved.getUpdateIntervalDays()).isNull();   // manual-apply by default
        assertThat(dto.getPbfName()).isEqualTo("us-west");
        // Phase 2c: paired routing_coverage row is inserted in the same tx.
        verify(routingRepository).insertEmptyRow("us-west");
        // DTO mirrors the paired row.
        assertThat(dto.isRoutingEnabled()).isTrue();
        assertThat(dto.isRoutingHasPolygon()).isFalse();
    }

    @Test
    void create_409_whenPbfNameAlreadyExists() {
        PbfFileCreateRequest req = new PbfFileCreateRequest();
        req.setPbfName("us-west");
        req.setGeofabrikUrl("https://example/us-west.osm.pbf");
        when(repository.existsById("us-west")).thenReturn(true);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(PbfFileService.PbfFileAlreadyExistsException.class)
                .hasMessageContaining("us-west");
        verify(repository, never()).save(any(PbfFile.class));
    }

    @Test
    void update_appliesOnlyProvidedFields_leavingOthersUntouched() {
        PbfFile existing = sample("us-west");
        existing.setActive(true);
        existing.setCheckIntervalDays(7);
        existing.setUpdateIntervalDays(30);
        when(repository.findById("us-west")).thenReturn(Optional.of(existing));
        when(repository.save(any(PbfFile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(routingRepository.findById("us-west")).thenReturn(Optional.empty());

        PbfFileUpdateRequest req = new PbfFileUpdateRequest();
        req.setActive(false);
        // Other fields left null — must NOT clobber existing values.

        service.update("us-west", req);

        ArgumentCaptor<PbfFile> savedCaptor = ArgumentCaptor.forClass(PbfFile.class);
        verify(repository).save(savedCaptor.capture());
        PbfFile saved = savedCaptor.getValue();
        assertThat(saved.isActive()).isFalse();
        // Defensive: untouched fields keep their pre-update values.
        assertThat(saved.getCheckIntervalDays()).isEqualTo(7);
        assertThat(saved.getUpdateIntervalDays()).isEqualTo(30);
        assertThat(saved.getGeofabrikUrl()).isEqualTo(existing.getGeofabrikUrl());
        // routingEnabled was null in the request → updateEnabled is not called.
        verify(routingRepository, never()).updateEnabled(any(String.class), anyBoolean());
    }

    @Test
    void update_routingEnabled_flag_writesToRoutingCoverage() {
        // Phase 2c: PATCH with routingEnabled is the supported way to flip
        // the dispatcher toggle. The service fans this single PATCH out to
        // both tables in the same @Transactional scope.
        PbfFile existing = sample("us-west");
        when(repository.findById("us-west")).thenReturn(Optional.of(existing));
        when(repository.save(any(PbfFile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(routingRepository.findById("us-west")).thenReturn(Optional.empty());

        PbfFileUpdateRequest req = new PbfFileUpdateRequest();
        req.setRoutingEnabled(Boolean.FALSE);

        service.update("us-west", req);

        verify(routingRepository).updateEnabled("us-west", false);
    }

    @Test
    void update_404_whenPbfNameNotFound() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update("missing", new PbfFileUpdateRequest()))
                .isInstanceOf(PbfFileService.PbfFileNotFoundException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void delete_404_whenPbfNameNotFound() {
        when(repository.existsById("missing")).thenReturn(false);

        assertThatThrownBy(() -> service.delete("missing"))
                .isInstanceOf(PbfFileService.PbfFileNotFoundException.class);
        verify(repository, never()).deleteById(any(String.class));
    }

    @Test
    void scheduleNow_setsNextUpdateAt_toApproximatelyNow() {
        PbfFile existing = sample("us-west");
        when(repository.findById("us-west")).thenReturn(Optional.of(existing));
        when(repository.save(any(PbfFile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(routingRepository.findById("us-west")).thenReturn(Optional.empty());

        ZonedDateTime before = ZonedDateTime.now();
        service.scheduleNow("us-west");
        ZonedDateTime after = ZonedDateTime.now();

        ArgumentCaptor<PbfFile> savedCaptor = ArgumentCaptor.forClass(PbfFile.class);
        verify(repository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getNextUpdateAt())
                .isBetween(before.minusSeconds(1), after.plusSeconds(1));
    }

    @Test
    void retryStuckApply_clearsInFlightMarker_andSchedulesNow() {
        // Simulate a row stuck in "apply in flight" — admin clicks Retry
        // before the 4 h auto-recovery kicks in.
        PbfFile existing = sample("us-west");
        existing.setLastApplyStartedAt(ZonedDateTime.now().minusHours(2));
        existing.setLastApplyFinishedAt(null);
        when(repository.findById("us-west")).thenReturn(Optional.of(existing));
        when(repository.save(any(PbfFile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(routingRepository.findById("us-west")).thenReturn(Optional.empty());

        service.retryStuckApply("us-west");

        ArgumentCaptor<PbfFile> savedCaptor = ArgumentCaptor.forClass(PbfFile.class);
        verify(repository).save(savedCaptor.capture());
        PbfFile saved = savedCaptor.getValue();
        assertThat(saved.getLastApplyStartedAt())
                .as("in-flight marker must be cleared so the row is no longer flagged stuck")
                .isNull();
        assertThat(saved.getNextUpdateAt())
                .as("apply must be rescheduled for the next cron tick")
                .isNotNull();
    }
}
