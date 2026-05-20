package com.pjr22.tripweather.dto;

/**
 * Body of {@code POST /api/favorites}. Phase 1 of FAVORITES_AND_ROUTE_MGMT.md.
 *
 * <p>{@code label} is required and must be unique per user (case-insensitive).
 * {@code locationName} is required; if the source waypoint hasn't reverse-
 * geocoded yet, the client (or the service, as a fallback) sets it to a
 * {@code "lat, lon"} coordinate string so the column never carries empty.
 * {@code latitude} and {@code longitude} are required; {@code elevation}
 * is optional.
 */
public record CreateFavoriteRequest(
        String label,
        String locationName,
        Double latitude,
        Double longitude,
        Double elevation) {
}
