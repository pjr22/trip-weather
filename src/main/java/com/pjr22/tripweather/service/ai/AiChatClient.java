package com.pjr22.tripweather.service.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * A client that runs one chat completion against a provider and returns the
 * model's text. AI_ASSIST_PLAN.md, Phase 2. Implementations:
 * {@link OpenAiCompatibleChatClient} (OpenAI / Custom / Ollama) and
 * {@link AnthropicChatClient}.
 */
public interface AiChatClient {

    /**
     * Run the completion and return the assistant's text content.
     *
     * @throws ResponseStatusException 400 on auth failure (bad key), 502 on any
     *         other provider error or unreachable provider
     */
    String complete(AiChatCall call);

    /**
     * Shared provider-error mapping for implementations: 401/403 → 400 ("check
     * the API key"), any other HTTP error → 502, connection/timeout → 502.
     */
    static ResponseStatusException mapError(RuntimeException e) {
        if (e instanceof RestClientResponseException http) {
            int status = http.getStatusCode().value();
            if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
                return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "The AI provider rejected the API key (HTTP " + status + ").");
            }
            return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The AI provider returned an error (HTTP " + status + ").");
        }
        if (e instanceof ResourceAccessException) {
            return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Could not reach the AI provider.");
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "The AI provider call failed: " + e.getMessage());
    }
}
