package com.pjr22.tripweather.dto;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * JSON shape returned by every read endpoint under {@code /api/favorites}.
 * Phase 1 of FAVORITES_AND_ROUTE_MGMT.md.
 *
 * <p>{@code created} matches {@code RouteDto.created} ({@link ZonedDateTime})
 * for SPA consistency. {@code elevation} is nullable — favorites stored
 * before an elevation pass has run, or freshly created from a map-click,
 * carry no elevation; consumers can re-derive it later.
 */
public record FavoriteWaypointDto(
        UUID id,
        String label,
        String locationName,
        Double latitude,
        Double longitude,
        Double elevation,
        ZonedDateTime created) {
}
