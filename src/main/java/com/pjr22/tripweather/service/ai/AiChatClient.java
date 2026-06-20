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
     * Run the completion and return the assistant's text content plus any
     * reported token usage.
     *
     * @throws ResponseStatusException 400 on auth failure (bad key), 502 on any
     *         other provider error or unreachable provider
     */
    AiChatResult complete(AiChatCall call);

    /**
     * Trim an upstream error body / raw model output for logging — keep enough to
     * diagnose (provider error JSON, malformed completion) without flooding the
     * log on a pathological response. Null becomes a stable placeholder.
     */
    static String truncate(String s) {
        if (s == null) {
            return "(none)";
        }
        String t = s.strip();
        return t.length() <= 2000 ? t : t.substring(0, 2000) + "…(truncated)";
    }

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
