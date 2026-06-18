package com.pjr22.tripweather.dto;

import java.time.ZonedDateTime;
import java.util.UUID;

import com.pjr22.tripweather.model.AiProvider;

/**
 * JSON shape returned by every read endpoint under {@code /api/ai/providers}.
 * AI_ASSIST_PLAN.md, Phase 1.
 *
 * <p>Deliberately carries <b>no API key</b>. Instead {@code apiKeySet} tells the
 * client whether a key is stored, so the edit form can show a "•••• stored"
 * placeholder and leave the field blank to keep the existing value. The key is
 * write-only and never leaves the server after it's saved.
 *
 * <p>{@code baseUrl} is populated only for {@link AiProvider#CUSTOM}; null for
 * the other providers.
 */
public record ProviderConfigSummary(
        UUID id,
        AiProvider provider,
        String nickname,
        String model,
        String baseUrl,
        boolean apiKeySet,
        ZonedDateTime created) {
}
