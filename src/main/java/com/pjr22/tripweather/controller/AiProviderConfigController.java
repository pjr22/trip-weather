package com.pjr22.tripweather.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pjr22.tripweather.dto.CreateProviderConfigRequest;
import com.pjr22.tripweather.dto.ModelDiscoveryRequest;
import com.pjr22.tripweather.dto.ModelListResponse;
import com.pjr22.tripweather.dto.ProviderConfigSummary;
import com.pjr22.tripweather.dto.UpdateProviderConfigRequest;
import com.pjr22.tripweather.service.AiProviderConfigService;
import com.pjr22.tripweather.service.ai.AiModelDiscoveryService;

/**
 * REST controller for AI-provider-configuration CRUD. AI_ASSIST_PLAN.md,
 * Phase 1.
 *
 * <p>Every endpoint requires authentication; SecurityConfig blocks anonymous
 * callers at {@code /api/ai/**} so a 401 surfaces before any method runs.
 * Ownership / not-found / duplicate-nickname / validation are mapped via
 * {@code @ResponseStatus} on the exception classes in
 * {@link AiProviderConfigService}; the controller doesn't catch them. API keys
 * are write-only — no endpoint here returns a stored key.
 */
@RestController
@RequestMapping(value = "/api/ai/providers", produces = MediaType.APPLICATION_JSON_VALUE)
public class AiProviderConfigController {

    private static final Logger logger = LoggerFactory.getLogger(AiProviderConfigController.class);

    private final AiProviderConfigService service;
    private final AiModelDiscoveryService discoveryService;

    public AiProviderConfigController(AiProviderConfigService service,
                                      AiModelDiscoveryService discoveryService) {
        this.service = service;
        this.discoveryService = discoveryService;
    }

    /** List the caller's provider configs (no keys). */
    @GetMapping
    public List<ProviderConfigSummary> list() {
        return service.listForCurrentUser();
    }

    /**
     * The provider types this server offers, for the config form's provider
     * picker. Always OpenAI / Anthropic / Custom; Ollama only when the operator
     * configured {@code TRIP_AI_OLLAMA_URL}. Shape: {@code {"providers": [...]}}.
     */
    @GetMapping("/available")
    public Map<String, Object> available() {
        return Map.of("providers", service.availableProviders());
    }

    /**
     * Discover available models from in-progress create-form credentials, so the
     * config form's model dropdown can be populated before the config is saved.
     * 400 on bad input / auth (check the key), 502 if the provider is
     * unreachable.
     */
    @PostMapping("/models")
    public ModelListResponse discoverModels(@RequestBody ModelDiscoveryRequest request) {
        logger.info("Discover models: provider={}", request == null ? null : request.provider());
        return new ModelListResponse(discoveryService.discover(request));
    }

    /**
     * Discover available models for an existing config owned by the caller,
     * using its stored key (no need to re-enter it on the edit form). 404 if not
     * owned.
     */
    @GetMapping("/{id}/models")
    public ModelListResponse discoverModelsForConfig(@PathVariable UUID id) {
        logger.info("Discover models for config {}", id);
        return new ModelListResponse(discoveryService.discoverForConfig(id));
    }

    /** Fetch one config owned by the caller. 404 if absent / not owned. */
    @GetMapping("/{id}")
    public ProviderConfigSummary get(@PathVariable UUID id) {
        return service.get(id);
    }

    /** Create a config. 201 on success, 409 on duplicate nickname, 400 on invalid input. */
    @PostMapping
    public ResponseEntity<ProviderConfigSummary> create(@RequestBody CreateProviderConfigRequest request) {
        logger.info("Create AI provider config: provider={}, nickname='{}'",
                request == null ? null : request.provider(),
                request == null ? null : request.nickname());
        ProviderConfigSummary saved = service.create(request);
        return ResponseEntity.status(201).body(saved);
    }

    /** Update a config. 200 on success, 404 on not-owned, 409 on duplicate nickname. */
    @PutMapping("/{id}")
    public ProviderConfigSummary update(@PathVariable UUID id,
                                        @RequestBody UpdateProviderConfigRequest request) {
        logger.info("Update AI provider config {}", id);
        return service.update(id, request);
    }

    /** Soft-delete a config. 204 on success, 404 on not-owned. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        logger.info("Delete AI provider config {}", id);
        service.softDelete(id);
        return ResponseEntity.noContent().build();
    }
}
