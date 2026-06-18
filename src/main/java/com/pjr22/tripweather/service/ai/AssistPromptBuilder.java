package com.pjr22.tripweather.service.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds the system and user prompts for an AI assist request, plus the
 * "repair" prompt used to coerce a non-JSON first response into valid JSON.
 * AI_ASSIST_PLAN.md, Phase 2.
 *
 * <p>This is the main tuning surface for the feature — kept small and explicit
 * so the prompt can be iterated over the curl/debug loop without touching the
 * orchestration.
 */
@Component
public class AssistPromptBuilder {

    private final int maxWaypoints;

    public AssistPromptBuilder(@Value("${trip.ai.max-waypoints:25}") int maxWaypoints) {
        this.maxWaypoints = maxWaypoints;
    }

    /** System instruction for the initial extraction. */
    public String systemPrompt() {
        return """
            You are a trip-planning assistant. The user describes a road trip in plain text. \
            Identify the stops and destinations and return them as STRICT JSON only — no prose, \
            no explanations, no markdown code fences — in exactly this shape:

            {"locations":[{"name":"<place name or street address>","city":"<city>","state":"<state, province, or region>"}]}

            Rules:
            - Order the locations in the sequence they should be visited along the route, from start to finish.
            - Return at most %d locations.
            - Use real, geocodable place names (landmarks, cities, addresses). Avoid vague descriptions.
            - When the user names a broad area, choose well-known specific places within it.
            - Always include "city" and "state"/region when they apply; use an empty string if truly not applicable.
            - Output only the JSON object, nothing else.
            """.formatted(maxWaypoints);
    }

    /** The user's free-text trip description. */
    public String userPrompt(String freeText) {
        return freeText == null ? "" : freeText.trim();
    }

    /**
     * System instruction for the one-shot repair pass: given a previous response
     * that wasn't parseable JSON, extract the locations into the required shape.
     */
    public String repairSystemPrompt() {
        return """
            The following text was supposed to be a JSON object of trip locations but could not be parsed. \
            Extract the locations and return ONLY a valid JSON object in exactly this shape, with no prose \
            and no code fences:

            {"locations":[{"name":"<place>","city":"<city>","state":"<state/region>"}]}
            """;
    }
}
