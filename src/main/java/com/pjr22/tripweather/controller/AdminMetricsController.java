package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.dto.MetricsSnapshotDto;
import com.pjr22.tripweather.service.MetricsSnapshotService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator-facing metrics snapshot for the admin console's Metrics tab.
 * Phase 3 of ADMIN_CONSOLE.md.
 *
 * <p>Single endpoint: {@code GET /api/admin/metrics} returns the live
 * snapshot. The admin chain in
 * {@link com.pjr22.tripweather.config.SecurityConfig} restricts this to
 * {@code ROLE_ADMIN}.
 */
@RestController
@RequestMapping(value = "/api/admin", produces = MediaType.APPLICATION_JSON_VALUE)
public class AdminMetricsController {

    private final MetricsSnapshotService snapshotService;

    public AdminMetricsController(MetricsSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @GetMapping("/metrics")
    public MetricsSnapshotDto snapshot() {
        return snapshotService.snapshot();
    }
}
