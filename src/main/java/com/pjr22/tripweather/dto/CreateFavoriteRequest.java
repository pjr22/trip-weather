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
 *
 * <p>The five {@code timezone*} fields are optional. When the source
 * waypoint has them, the client sends them through so the favorite can
 * render times without a follow-up timezone-API round-trip. Missing
 * fields stay null in the database.
 */
public record CreateFavoriteRequest(
        String label,
        String locationName,
        Double latitude,
        Double longitude,
        Double elevation,
        String timezoneName,
        String timezoneStdOffset,
        String timezoneDstOffset,
        String timezoneStdAbbr,
        String timezoneDstAbbr) {
}
