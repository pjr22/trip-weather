package com.pjr22.tripweather.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WaypointDto {
    
    private UUID id;
    private Integer sequence;
    private String date;
    private String time;
    private String timezone;
    private Integer durationMin;
    private String locationName;
    private Double latitude;
    private Double longitude;
    private Double elevation;
    private UUID routeId;

    /**
     * UUID of the viewer's matching favorite, or {@code null} if no match.
     * Populated only by {@code RoutePersistenceService.loadRoute()} — every
     * other path (save, search-result, fresh entity load) leaves this null.
     * Anonymous viewers always see null. The match is exact equality on the
     * waypoint's {@code (latitude, longitude, locationName)} tuple against
     * the viewer's own favorites, so a shared-link viewer sees their own
     * favorites reflected, not the route owner's.
     *
     * <p>Phase 3a of FAVORITES_AND_ROUTE_MGMT.md.
     */
    private UUID favoriteId;
}
