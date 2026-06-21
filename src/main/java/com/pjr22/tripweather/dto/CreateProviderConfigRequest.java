package com.pjr22.tripweather.dto;

import com.pjr22.tripweather.model.AiProvider;

/**
 * Body of {@code POST /api/ai/providers}. AI_ASSIST_PLAN.md, Phase 1.
 *
 * <p>{@code provider}, {@code nickname}, and {@code model} are required.
 * {@code nickname} must be unique per user (case-insensitive).
 *
 * <p>{@code apiKey} is the plaintext key — write-only: it is encrypted at rest
 * by {@code AiKeyCipher} and never returned by any read endpoint. Required for
 * {@link AiProvider#OPENAI} / {@link AiProvider#ANTHROPIC}; optional for
 * {@link AiProvider#CUSTOM} / {@link AiProvider#OLLAMA}.
 *
 * <p>{@code baseUrl} is required for {@link AiProvider#CUSTOM} (an OpenAI-
 * compatible root, subject to the SSRF guard) and ignored for the other
 * providers, which resolve their endpoint from server config.
 *
 * <p>{@code inputCostPerMtok} / {@code outputCostPerMtok} are optional prices in
 * USD per 1,000,000 tokens (input and output). When supplied, the assist
 * response reports an estimated dollar cost for the run. Null/absent = not
 * configured.
 */
public record CreateProviderConfigRequest(
        AiProvider provider,
        String nickname,
        String model,
        String apiKey,
        String baseUrl,
        Double inputCostPerMtok,
        Double outputCostPerMtok) {
}
