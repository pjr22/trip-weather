package com.pjr22.tripweather.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/**
 * Body for {@code POST /api/admin/pbfs}. Phase 2b of ADMIN_CONSOLE.md.
 *
 * <p>{@code pbfName} is the primary key — used in URLs, in cron logs, and as
 * the back-link target from {@code routing_coverage.pbf_name}. Keep it short
 * and slug-like.
 */
@Data
@NoArgsConstructor
public class PbfFileCreateRequest {

    @NotBlank
    @Size(max = 64)
    private String pbfName;

    @NotBlank
    private String geofabrikUrl;

    private Boolean active;

    @Min(1)
    private Integer checkIntervalDays;

    @Min(1)
    private Integer updateIntervalDays;

    private ZonedDateTime nextCheckAt;

    private ZonedDateTime nextUpdateAt;
}
