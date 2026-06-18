package com.pjr22.tripweather.service.ai;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.pjr22.tripweather.dto.ModelDiscoveryRequest;
import com.pjr22.tripweather.model.AiProvider;
import com.pjr22.tripweather.model.AiProviderConfig;
import com.pjr22.tripweather.model.User;
import com.pjr22.tripweather.repository.AiProviderConfigRepository;
import com.pjr22.tripweather.security.AiKeyCipher;
import com.pjr22.tripweather.security.CurrentUserService;

/**
 * Fetches the list of available model IDs from an AI provider, for the config
 * form's model dropdown. AI_ASSIST_PLAN.md, Phase 1b.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #discover(ModelDiscoveryRequest)} — uses the in-progress
 *       create-form credentials (POST {@code /api/ai/providers/models}).</li>
 *   <li>{@link #discoverForConfig(UUID)} — uses a stored config's decrypted key
 *       (GET {@code /api/ai/providers/{id}/models}), with ownership → 404.</li>
 * </ul>
 *
 * <p>Per-provider list endpoints: OpenAI / Custom {@code GET {base}/models},
 * Anthropic {@code GET {base}/v1/models}, Ollama {@code GET {base}/api/tags}.
 * The {@code CUSTOM} base URL passes through {@link OutboundUrlGuard} first;
 * operator-set endpoints (OpenAI / Anthropic / Ollama) are trusted. Provider
 * failures map to a clear status: 401/403 → 400 ("check the API key"), anything
 * else → 502.
 */
@Service
public class AiModelDiscoveryService {

    private final RestClient aiRestClient;
    private final OutboundUrlGuard urlGuard;
    private final CurrentUserService currentUserService;
    private final AiProviderConfigRepository repository;
    private final AiKeyCipher keyCipher;

    private final String openaiBaseUrl;
    private final String anthropicBaseUrl;
    private final String anthropicVersion;
    private final String ollamaUrl;

    public AiModelDiscoveryService(RestClient aiRestClient,
                                   OutboundUrlGuard urlGuard,
                                   CurrentUserService currentUserService,
                                   AiProviderConfigRepository repository,
                                   AiKeyCipher keyCipher,
                                   @Value("${trip.ai.openai-base-url:https://api.openai.com/v1}") String openaiBaseUrl,
                                   @Value("${trip.ai.anthropic-base-url:https://api.anthropic.com}") String anthropicBaseUrl,
                                   @Value("${trip.ai.anthropic-version:2023-06-01}") String anthropicVersion,
                                   @Value("${trip.ai.ollama-url:}") String ollamaUrl) {
        this.aiRestClient = aiRestClient;
        this.urlGuard = urlGuard;
        this.currentUserService = currentUserService;
        this.repository = repository;
        this.keyCipher = keyCipher;
        this.openaiBaseUrl = openaiBaseUrl;
        this.anthropicBaseUrl = anthropicBaseUrl;
        this.anthropicVersion = anthropicVersion;
        this.ollamaUrl = ollamaUrl == null ? "" : ollamaUrl.trim();
    }

    /** Discover models from in-progress create-form credentials. */
    public List<String> discover(ModelDiscoveryRequest req) {
        if (req == null) {
            throw badRequest("request body is required");
        }
        validateRequest(req.provider(), blankToNull(req.apiKey()), req.baseUrl());
        return listModels(req.provider(), blankToNull(req.apiKey()), req.baseUrl());
    }

    /** Discover models for a stored config owned by the current user (404 if not owned). */
    public List<String> discoverForConfig(UUID id) {
        User user = requireCurrentUser();
        AiProviderConfig config = repository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "AI provider config not found"));

        String apiKey = (config.getApiKeyEncrypted() != null && !config.getApiKeyEncrypted().isBlank())
                ? keyCipher.decrypt(config.getApiKeyEncrypted())
                : null;
        validateRequest(config.getProvider(), apiKey, config.getBaseUrl());
        return listModels(config.getProvider(), apiKey, config.getBaseUrl());
    }

    /** Whether the operator configured a usable Ollama endpoint. */
    public boolean isOllamaEnabled() {
        return !ollamaUrl.isBlank() && isValidHttpUrl(ollamaUrl);
    }

    // ------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------

    private void validateRequest(AiProvider provider, String apiKey, String baseUrl) {
        if (provider == null) {
            throw badRequest("provider is required");
        }
        switch (provider) {
            case OLLAMA -> {
                if (!isOllamaEnabled()) {
                    throw badRequest("Ollama is not enabled on this server (TRIP_AI_OLLAMA_URL is not set)");
                }
            }
            case CUSTOM -> {
                if (isBlank(baseUrl)) {
                    throw badRequest("baseUrl is required for Custom providers");
                }
            }
            case OPENAI, ANTHROPIC -> {
                if (isBlank(apiKey)) {
                    throw badRequest("an API key is required to list models for " + provider);
                }
            }
        }
    }

    private List<String> listModels(AiProvider provider, String apiKey, String customBaseUrl) {
        record Endpoint(String url, Consumer<HttpHeaders> headers, String arrayField, String idField) {}

        Endpoint ep = switch (provider) {
            case OPENAI -> new Endpoint(
                    join(openaiBaseUrl, "/models"),
                    h -> h.setBearerAuth(apiKey),
                    "data", "id");
            case CUSTOM -> {
                urlGuard.validate(customBaseUrl);
                yield new Endpoint(
                        join(customBaseUrl, "/models"),
                        h -> { if (apiKey != null) h.setBearerAuth(apiKey); },
                        "data", "id");
            }
            case ANTHROPIC -> new Endpoint(
                    join(anthropicBaseUrl, "/v1/models"),
                    h -> {
                        h.set("x-api-key", apiKey);
                        h.set("anthropic-version", anthropicVersion);
                    },
                    "data", "id");
            case OLLAMA -> new Endpoint(
                    join(ollamaUrl, "/api/tags"),
                    h -> { },
                    "models", "name");
        };

        JsonNode body = fetch(ep.url(), ep.headers());
        return parseIds(body, ep.arrayField(), ep.idField());
    }

    private JsonNode fetch(String url, Consumer<HttpHeaders> headers) {
        try {
            return aiRestClient.get()
                    .uri(URI.create(url))
                    .headers(headers)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Authentication failed — check the API key.");
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The AI provider returned an error (" + status + ") while listing models.");
        } catch (ResourceAccessException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not reach the AI provider to list models.");
        }
    }

    private static List<String> parseIds(JsonNode body, String arrayField, String idField) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The AI provider returned an empty response while listing models.");
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        JsonNode array = body.path(arrayField);
        if (array.isArray()) {
            for (JsonNode node : array) {
                String value = node.path(idField).asText(null);
                if (value != null && !value.isBlank()) {
                    ids.add(value.trim());
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private User requireCurrentUser() {
        return currentUserService.currentUser()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "AI provider config not found"));
    }

    private static String join(String base, String path) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b + path;
    }

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

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
