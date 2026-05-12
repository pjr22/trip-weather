package com.pjr22.tripweather.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/**
 * Body for {@code PATCH /api/admin/pbfs/{name}}. Phase 2b of ADMIN_CONSOLE.md.
 *
 * <p>Every field is optional — the controller treats {@code null} as
 * "don't touch this field". To explicitly clear a schedule, the admin can
 * use a dedicated action (e.g. setting {@code active=false}) or a future
 * "pause" endpoint; we deliberately don't use sentinel values here to keep
 * the PATCH semantics straightforward.
 */
@Data
@NoArgsConstructor
public class PbfFileUpdateRequest {

    private String geofabrikUrl;
    private Boolean active;

    @Min(1)
    private Integer checkIntervalDays;

    @Min(1)
    private Integer updateIntervalDays;

    private ZonedDateTime nextCheckAt;

    private ZonedDateTime nextUpdateAt;

    /**
     * Phase 2c: writes to {@code routing_coverage.enabled} for the paired
     * row. The pbf and routing tables share a primary key (and a CASCADE
     * FK) so admin sees one logical record on the Pbfs card; the service
     * fans this field out to the second table inside the same transaction.
     */
    private Boolean routingEnabled;
}
