package com.pjr22.tripweather.dto;

import java.util.List;

/**
 * Response of the model-discovery endpoints. AI_ASSIST_PLAN.md, Phase 1b.
 * The provider's available model IDs, in the order the provider returned them.
 */
public record ModelListResponse(List<String> models) {
}
