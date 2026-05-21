package com.pjr22.tripweather.dto;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Row of the {@code GET /api/routes} response. Phase 4 of
 * FAVORITES_AND_ROUTE_MGMT.md.
 *
 * <p>Carries only the fields the My Routes modal and the Load Route picker
 * need to render a list — no waypoints (loading the full route is a
 * follow-up {@code GET /api/routes/{uuid}}). The {@code created} type is
 * {@link ZonedDateTime} for consistency with {@code RouteDto} and the
 * other route DTOs.
 *
 * <p>The plan also called out a {@code totalDistanceMeters} field; that's
 * deferred to a follow-up because {@code Route} has no persisted distance
 * field today, so every row would carry null. Adding distance later is a
 * non-breaking extension: append a nullable field on the record without
 * touching existing clients.
 */
public record RouteSummaryDto(
        UUID id,
        String name,
        ZonedDateTime created,
        long waypointCount) {
}
