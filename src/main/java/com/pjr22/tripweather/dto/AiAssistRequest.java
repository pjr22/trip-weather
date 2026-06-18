package com.pjr22.tripweather.dto;

import java.util.UUID;

/**
 * Body of {@code POST /api/ai/assist}. AI_ASSIST_PLAN.md, Phase 2.
 *
 * @param providerConfigId the saved AI provider config to use (must be owned by
 *                         the caller)
 * @param prompt           the user's free-text trip description
 */
public record AiAssistRequest(
        UUID providerConfigId,
        String prompt) {
}
