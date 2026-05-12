package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.dto.MetricsSnapshotDto;
import com.pjr22.tripweather.service.MetricsSnapshotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Authorisation (ROLE_ADMIN) is enforced by the admin SecurityFilterChain;
 * this test covers the controller's pass-through to {@link
 * MetricsSnapshotService}, mirroring the pattern used by other admin
 * controller tests.
 */
@ExtendWith(MockitoExtension.class)
class AdminMetricsControllerTest {

    @Mock private MetricsSnapshotService service;

    @InjectMocks
    private AdminMetricsController controller;

    @Test
    void snapshot_passesThroughToService() {
        MetricsSnapshotDto fixture = new MetricsSnapshotDto(
                new MetricsSnapshotDto.HttpLatency(0, 0, 0, 0, 0, 0),
                new MetricsSnapshotDto.Routing(List.of()),
                new MetricsSnapshotDto.Heap(0, 0, null),
                List.of(),
                List.of());
        when(service.snapshot()).thenReturn(fixture);

        assertThat(controller.snapshot()).isSameAs(fixture);
        verify(service).snapshot();
    }
}
