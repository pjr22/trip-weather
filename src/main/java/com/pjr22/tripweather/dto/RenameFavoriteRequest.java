package com.pjr22.tripweather.dto;

/**
 * Body of {@code PUT /api/favorites/{id}}. Phase 1 of FAVORITES_AND_ROUTE_MGMT.md.
 *
 * <p>Only the label is mutable on a favorite — locationName / latitude /
 * longitude / elevation are immutable because a different place is a
 * different favorite (decision #8). Validates non-empty and length ≤ 255
 * server-side.
 */
public record RenameFavoriteRequest(String label) {
}
