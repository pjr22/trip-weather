package com.pjr22.tripweather.dto;

/**
 * Always-on detail about one AI assist run, surfaced to the UI so the user can
 * see what the model returned and what it cost. AI_ASSIST_PLAN.md, Phase 4.
 *
 * <p>Not gated behind {@code trip.ai.assist-debug} — these are returned on every
 * request. (The debug flag instead controls server-side <em>logging</em> of the
 * prompt + this detail.) Token figures are nullable because not every provider
 * reports a usage block.
 *
 * @param model            the model id the request used
 * @param rawResponse      the model's raw text output (the source the waypoints
 *                         were parsed from) — lets the user see what the AI
 *                         intended for a stop that geocoded oddly
 * @param promptTokens     input tokens, or null if the provider didn't report
 * @param completionTokens output tokens, or null if unreported
 * @param totalTokens      total tokens, or null if unreported
 * @param elapsedMs        wall-clock time spent in the model call(s), in ms
 */
public record AiAssistDetails(
        String model,
        String rawResponse,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        long elapsedMs) {
}
