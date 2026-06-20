package com.pjr22.tripweather.dto;

import java.util.List;

import com.pjr22.tripweather.model.RouteData;

/**
 * Response of {@code POST /api/ai/assist}. AI_ASSIST_PLAN.md, Phase 2.
 *
 * @param waypoints  geocoded waypoints in travel order
 * @param route      the calculated route over those waypoints, or null when
 *                   fewer than two geocoded successfully
 * @param unresolved AI-suggested locations that couldn't be geocoded, each
 *                   carrying its failed query and sequence (Phase 4a) so the
 *                   resolution modal can present them as editable rows
 * @param warnings   route-level notes only (e.g. "too many locations, used the
 *                   first N"; "could not calculate a route"). Per-location
 *                   "couldn't find" misses live in {@code unresolved} instead.
 * @param details    always-on run detail (model, raw response, token usage,
 *                   elapsed time) the UI surfaces on demand (Phase 4)
 */
public record AiAssistResponse(
        List<ResolvedWaypoint> waypoints,
        RouteData route,
        List<UnresolvedLocation> unresolved,
        List<String> warnings,
        AiAssistDetails details) {
}
