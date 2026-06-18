package com.pjr22.tripweather.service.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One location the AI returned, before geocoding. AI_ASSIST_PLAN.md, Phase 2.
 * Parsed from the model's JSON by {@link LocationListParser}; the orchestration
 * then geocodes each into a waypoint. Unknown JSON fields are ignored so a model
 * that adds extra keys (e.g. {@code notes}) doesn't break parsing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiLocation(
        String name,
        String city,
        String state) {
}
