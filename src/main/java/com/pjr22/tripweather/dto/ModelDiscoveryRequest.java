package com.pjr22.tripweather.dto;

import com.pjr22.tripweather.model.AiProvider;

/**
 * Body of {@code POST /api/ai/providers/models}. AI_ASSIST_PLAN.md, Phase 1b.
 *
 * <p>Carries the in-progress create-form credentials so the model dropdown can
 * be populated before the config is saved (the chicken-and-egg of needing the
 * key before there's a stored config). {@code apiKey} is required for OpenAI /
 * Anthropic; {@code baseUrl} is required for {@link AiProvider#CUSTOM} (and runs
 * through the SSRF guard). For editing an existing config without re-entering
 * the key, use {@code GET /api/ai/providers/{id}/models} instead.
 */
public record ModelDiscoveryRequest(
        AiProvider provider,
        String apiKey,
        String baseUrl) {
}
