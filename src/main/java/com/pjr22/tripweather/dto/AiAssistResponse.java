package com.pjr22.tripweather.dto;

import java.util.List;

import com.pjr22.tripweather.model.RouteData;

/**
 * Response of {@code POST /api/ai/assist}. AI_ASSIST_PLAN.md, Phase 2.
 *
 * @param waypoints geocoded waypoints in travel order
 * @param route     the calculated route over those waypoints, or null when
 *                  fewer than two geocoded successfully
 * @param warnings  human-readable notes (e.g. a location that couldn't be found,
 *                  or a too-short waypoint list)
 * @param debugPrompt       the prompt sent to the model — populated only when
 *                          {@code trip.ai.assist-debug=true} (prompt tuning)
 * @param debugRawResponse  the raw model text — populated only when debug is on
 */
public record AiAssistResponse(
        List<ResolvedWaypoint> waypoints,
        RouteData route,
        List<String> warnings,
        String debugPrompt,
        String debugRawResponse) {
}
