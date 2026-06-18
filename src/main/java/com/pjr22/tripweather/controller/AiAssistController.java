package com.pjr22.tripweather.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pjr22.tripweather.dto.AiAssistRequest;
import com.pjr22.tripweather.dto.AiAssistResponse;
import com.pjr22.tripweather.service.ai.AiAssistService;

/**
 * REST controller for the AI trip-planning assistant. AI_ASSIST_PLAN.md,
 * Phase 2.
 *
 * <p>Requires authentication; SecurityConfig gates {@code /api/ai/**} so a 401
 * surfaces before this runs (and a 403 when the feature is disabled). Validation
 * / ownership / provider / parse errors are mapped to status codes by the
 * service via {@code ResponseStatusException}; the controller doesn't catch them.
 */
@RestController
@RequestMapping(value = "/api/ai/assist", produces = MediaType.APPLICATION_JSON_VALUE)
public class AiAssistController {

    private static final Logger logger = LoggerFactory.getLogger(AiAssistController.class);

    private final AiAssistService assistService;

    public AiAssistController(AiAssistService assistService) {
        this.assistService = assistService;
    }

    @PostMapping
    public AiAssistResponse assist(@RequestBody AiAssistRequest request) {
        logger.info("AI assist request: providerConfigId={}",
                request == null ? null : request.providerConfigId());
        return assistService.assist(request);
    }
}
