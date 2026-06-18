package com.pjr22.tripweather.dto;

import com.pjr22.tripweather.model.AiProvider;

/**
 * Body of {@code PUT /api/ai/providers/{id}}. AI_ASSIST_PLAN.md, Phase 1.
 *
 * <p>PUT replaces the editable representation: {@code provider}, {@code
 * nickname}, {@code model}, and (for {@link AiProvider#CUSTOM}) {@code baseUrl}
 * are set from this body, with the same validation as create.
 *
 * <p>{@code apiKey} is special. It is write-only and never returned, so the
 * edit form can't round-trip it. A <b>blank/absent</b> {@code apiKey} means
 * "keep the stored key unchanged"; a <b>non-blank</b> value re-encrypts and
 * replaces it. (To clear a key on a provider that allows none, switch the
 * provider — there is no explicit "erase key" affordance in Phase 1.)
 */
public record UpdateProviderConfigRequest(
        AiProvider provider,
        String nickname,
        String model,
        String apiKey,
        String baseUrl) {
}
