package com.pjr22.tripweather.dto;

/**
 * Body of {@code PATCH /api/routes/{id}}. Phase 4 of
 * FAVORITES_AND_ROUTE_MGMT.md.
 *
 * <p>Rename-only. Other route fields (waypoints, owner, created) are
 * intentionally immutable through this endpoint — the existing
 * {@code POST /api/routes} stays the only path that replaces waypoints.
 */
public record RenameRouteRequest(String name) {
}
