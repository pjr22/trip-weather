package com.pjr22.tripweather.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * One row of the admin Favorites list. Returned by
 * {@code GET /api/admin/favorites}; intentionally distinct from the public
 * {@link FavoriteWaypointDto} so the admin shape can carry owner email +
 * soft-delete state without forcing those fields onto the user-facing API.
 *
 * <p>Phase 5 of FAVORITES_AND_ROUTE_MGMT.md — mirrors
 * {@link AdminRouteSummary}'s shape so the two admin views share the same
 * rendering idioms.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminFavoriteSummary {

    private UUID id;
    private String label;
    private String locationName;
    private Double latitude;
    private Double longitude;
    private String ownerEmail;
    private ZonedDateTime created;
    private ZonedDateTime deletedAt;
}
