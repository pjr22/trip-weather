package com.pjr22.tripweather.dto;

/**
 * One AI-suggested location that could not be geocoded. AI_ASSIST_PLAN.md,
 * Phase 4a. Carries the failed geocode {@code query} (prefilled into the
 * resolution modal's edit field) and its {@code sequence} (0-based index in the
 * AI's ordering) so the frontend can place it in travel order alongside the
 * resolved stops.
 */
public record UnresolvedLocation(
        int sequence,
        String query) {
}
