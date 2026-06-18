package com.pjr22.tripweather.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.pjr22.tripweather.dto.CreateProviderConfigRequest;
import com.pjr22.tripweather.dto.ProviderConfigSummary;
import com.pjr22.tripweather.dto.UpdateProviderConfigRequest;
import com.pjr22.tripweather.model.AiProvider;
import com.pjr22.tripweather.model.AiProviderConfig;
import com.pjr22.tripweather.model.User;
import com.pjr22.tripweather.repository.AiProviderConfigRepository;
import com.pjr22.tripweather.security.AiKeyCipher;
import com.pjr22.tripweather.security.CurrentUserService;

/**
 * Service for AI-provider-configuration CRUD. AI_ASSIST_PLAN.md, Phase 1.
 *
 * <p>Mirrors {@link FavoriteWaypointService}: every public method resolves the
 * user via {@link CurrentUserService#currentUser()} (auth-only, no guest
 * fallback) and rejects ids not owned by that user with a 404 (not 403), so the
 * API can't be used to enumerate other users' configs. API keys are encrypted
 * at rest via {@link AiKeyCipher} and never returned in a {@link
 * ProviderConfigSummary}.
 */
@Service
public class AiProviderConfigService {

    private static final Logger logger = LoggerFactory.getLogger(AiProviderConfigService.class);

    /** Matches {@code ai_provider_configs.nickname VARCHAR(255)}. */
    private static final int NICKNAME_MAX_LENGTH = 255;
    /** Matches {@code ai_provider_configs.model VARCHAR(255)}. */
    private static final int MODEL_MAX_LENGTH = 255;
    /** Matches {@code ai_provider_configs.base_url VARCHAR(1023)}. */
    private static final int BASE_URL_MAX_LENGTH = 1023;

    private final AiProviderConfigRepository repository;
    private final CurrentUserService currentUserService;
    private final AiKeyCipher keyCipher;

    /**
     * Operator-set Ollama service URL. Non-blank + valid http/https ⇒ the
     * Ollama provider is offered and usable. Blank/invalid ⇒ Ollama is
     * disabled and rejected at create/update.
     */
    private final String ollamaUrl;

    public AiProviderConfigService(AiProviderConfigRepository repository,
                                   CurrentUserService currentUserService,
                                   AiKeyCipher keyCipher,
                                   @Value("${trip.ai.ollama-url:}") String ollamaUrl) {
        this.repository = repository;
        this.currentUserService = currentUserService;
        this.keyCipher = keyCipher;
        this.ollamaUrl = ollamaUrl == null ? "" : ollamaUrl.trim();
    }

    /** List the current user's provider configs (no keys). */
    @Transactional(readOnly = true)
    public List<ProviderConfigSummary> listForCurrentUser() {
        User user = requireCurrentUser();
        return repository.findAllByUser(user.getId()).stream()
                .map(AiProviderConfigService::toSummary)
                .toList();
    }

    /** Fetch one config owned by the current user, or 404. */
    @Transactional(readOnly = true)
    public ProviderConfigSummary get(UUID id) {
        return toSummary(requireOwned(id));
    }

    /**
     * The provider types this server can offer. Always OpenAI / Anthropic /
     * Custom; Ollama only when {@code trip.ai.ollama-url} is a valid URL.
     */
    public List<AiProvider> availableProviders() {
        List<AiProvider> providers = new ArrayList<>(List.of(
                AiProvider.OPENAI, AiProvider.ANTHROPIC, AiProvider.CUSTOM));
        if (isOllamaEnabled()) {
            providers.add(AiProvider.OLLAMA);
        }
        return providers;
    }

    /** Whether the operator has configured a usable Ollama endpoint. */
    public boolean isOllamaEnabled() {
        return !ollamaUrl.isBlank() && isValidHttpUrl(ollamaUrl);
    }

    /**
     * Create a config owned by the current user. Validates required fields,
     * provider-specific rules, and nickname uniqueness; encrypts the API key.
     */
    @Transactional
    public ProviderConfigSummary create(CreateProviderConfigRequest req) {
        User user = requireCurrentUser();
        if (req == null) {
            throw new InvalidProviderConfigException("request body is required");
        }

        AiProvider provider = requireProvider(req.provider());
        String nickname = requireNickname(req.nickname());
        String model = requireModel(req.model());
        String baseUrl = normalizeBaseUrl(provider, req.baseUrl());
        String apiKey = blankToNull(req.apiKey());
        requireKeyIfNeeded(provider, apiKey);

        if (repository.existsByUserIdAndNicknameIgnoreCase(user.getId(), nickname)) {
            throw new DuplicateNicknameException(
                    "You already have an AI provider named \"" + nickname + "\".");
        }

        AiProviderConfig config = new AiProviderConfig();
        config.setUser(user);
        config.setProvider(provider);
        config.setNickname(nickname);
        config.setModel(model);
        config.setBaseUrl(baseUrl);
        config.setApiKeyEncrypted(apiKey == null ? null : keyCipher.encrypt(apiKey));
        // id + created set by @PrePersist

        AiProviderConfig saved = repository.save(config);
        logger.info("AI provider config {} ({}) created for user {}",
                saved.getId(), provider, user.getId());
        return toSummary(saved);
    }

    /**
     * Replace the editable representation of a config owned by the current
     * user. A blank {@code apiKey} keeps the stored key; a non-blank one
     * re-encrypts.
     */
    @Transactional
    public ProviderConfigSummary update(UUID id, UpdateProviderConfigRequest req) {
        User user = requireCurrentUser();
        if (req == null) {
            throw new InvalidProviderConfigException("request body is required");
        }

        AiProviderConfig config = requireOwned(id);

        AiProvider provider = requireProvider(req.provider());
        String nickname = requireNickname(req.nickname());
        String model = requireModel(req.model());
        String baseUrl = normalizeBaseUrl(provider, req.baseUrl());

        // Effective key after this update: a new non-blank key replaces the
        // stored one; otherwise the existing ciphertext is kept.
        String newApiKey = blankToNull(req.apiKey());
        String effectiveEncrypted = (newApiKey != null)
                ? keyCipher.encrypt(newApiKey)
                : config.getApiKeyEncrypted();
        // Validate against what the config WILL have, not just the request.
        boolean willHaveKey = effectiveEncrypted != null;
        if (requiresApiKey(provider) && !willHaveKey) {
            throw new InvalidProviderConfigException(
                    "an API key is required for " + provider + " providers");
        }

        if (!nickname.equalsIgnoreCase(config.getNickname())
                && repository.existsByUserIdAndNicknameIgnoreCase(user.getId(), nickname)) {
            throw new DuplicateNicknameException(
                    "You already have an AI provider named \"" + nickname + "\".");
        }

        config.setProvider(provider);
        config.setNickname(nickname);
        config.setModel(model);
        config.setBaseUrl(baseUrl);
        config.setApiKeyEncrypted(effectiveEncrypted);

        AiProviderConfig saved = repository.save(config);
        logger.info("AI provider config {} updated by user {}", saved.getId(), user.getId());
        return toSummary(saved);
    }

    /** Soft-delete a config owned by the current user, or 404. */
    @Transactional
    public void softDelete(UUID id) {
        AiProviderConfig config = requireOwned(id);
        config.setDeletedAt(java.time.ZonedDateTime.now());
        repository.save(config);
        logger.info("AI provider config {} soft-deleted by user {}",
                config.getId(), config.getUser().getId());
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private User requireCurrentUser() {
        return currentUserService.currentUser()
                .orElseThrow(() -> new ProviderConfigNotFoundException("AI provider config not found"));
    }

    private AiProviderConfig requireOwned(UUID id) {
        User user = requireCurrentUser();
        return repository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ProviderConfigNotFoundException("AI provider config not found"));
    }

    private AiProvider requireProvider(AiProvider provider) {
        if (provider == null) {
            throw new InvalidProviderConfigException("provider is required");
        }
        if (provider == AiProvider.OLLAMA && !isOllamaEnabled()) {
            throw new InvalidProviderConfigException(
                    "Ollama is not enabled on this server (TRIP_AI_OLLAMA_URL is not set)");
        }
        return provider;
    }

    private String requireNickname(String nickname) {
        String n = requireNonBlank(nickname, "nickname");
        if (n.length() > NICKNAME_MAX_LENGTH) {
            throw new InvalidProviderConfigException(
                    "nickname is too long (max " + NICKNAME_MAX_LENGTH + " characters)");
        }
        return n;
    }

    private String requireModel(String model) {
        String m = requireNonBlank(model, "model");
        if (m.length() > MODEL_MAX_LENGTH) {
            throw new InvalidProviderConfigException(
                    "model is too long (max " + MODEL_MAX_LENGTH + " characters)");
        }
        return m;
    }

    private void requireKeyIfNeeded(AiProvider provider, String apiKey) {
        if (requiresApiKey(provider) && apiKey == null) {
            throw new InvalidProviderConfigException(
                    "an API key is required for " + provider + " providers");
        }
    }

    private static boolean requiresApiKey(AiProvider provider) {
        return provider == AiProvider.OPENAI || provider == AiProvider.ANTHROPIC;
    }

    /**
     * Base URL is user-supplied only for CUSTOM (required, validated). For every
     * other provider the endpoint comes from server config, so any supplied
     * value is ignored and the column is nulled out.
     */
    private String normalizeBaseUrl(AiProvider provider, String rawBaseUrl) {
        if (provider != AiProvider.CUSTOM) {
            return null;
        }
        String url = requireNonBlank(rawBaseUrl, "baseUrl");
        if (url.length() > BASE_URL_MAX_LENGTH) {
            throw new InvalidProviderConfigException(
                    "baseUrl is too long (max " + BASE_URL_MAX_LENGTH + " characters)");
        }
        if (!isValidHttpUrl(url)) {
            throw new InvalidProviderConfigException(
                    "baseUrl must be a valid http or https URL");
        }
        return url;
    }

    /**
     * Format check only: http/https scheme and a non-blank host. The full SSRF
     * guard (single-label-host rejection, DNS-resolve-to-public-IP) is applied
     * at outbound-request time by {@code OutboundUrlGuard} (Phase 1b), since it
     * does network resolution that doesn't belong in a save path.
     */
    private static boolean isValidHttpUrl(String url) {
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            return scheme != null
                    && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static String requireNonBlank(String s, String field) {
        if (s == null || s.isBlank()) {
            throw new InvalidProviderConfigException(field + " is required");
        }
        return s.trim();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static ProviderConfigSummary toSummary(AiProviderConfig c) {
        boolean apiKeySet = c.getApiKeyEncrypted() != null && !c.getApiKeyEncrypted().isBlank();
        return new ProviderConfigSummary(
                c.getId(),
                c.getProvider(),
                c.getNickname(),
                c.getModel(),
                c.getBaseUrl(),
                apiKeySet,
                c.getCreated());
    }

    // ------------------------------------------------------------------------
    // Exceptions — same shape as the nested classes in FavoriteWaypointService.
    // ------------------------------------------------------------------------

    /** 404 — config absent or owned by another user (shared status hides which). */
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class ProviderConfigNotFoundException extends RuntimeException {
        public ProviderConfigNotFoundException(String message) { super(message); }
    }

    /** 409 — the user already owns a config with this nickname (case-insensitive). */
    @ResponseStatus(HttpStatus.CONFLICT)
    public static class DuplicateNicknameException extends RuntimeException {
        public DuplicateNicknameException(String message) { super(message); }
    }

    /** 400 — missing required field, length-cap violation, or provider-rule breach. */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static class InvalidProviderConfigException extends RuntimeException {
        public InvalidProviderConfigException(String message) { super(message); }
    }
}
