package com.pjr22.tripweather.dto;

/**
 * One AI-suggested location that was successfully geocoded into a waypoint.
 * AI_ASSIST_PLAN.md, Phase 2. Shaped for the frontend to load into the working
 * route (Phase 4). {@code elevation} is nullable — Phase 2 does not resolve it
 * (the calculated route geometry carries elevation); consumers can derive it on
 * demand.
 */
public record ResolvedWaypoint(
        double latitude,
        double longitude,
        String locationName,
        String city,
        String state,
        Double elevation) {
}
