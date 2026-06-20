package com.pjr22.tripweather.service.ai;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Chat client for OpenAI and any OpenAI-compatible endpoint (Custom, Ollama).
 * AI_ASSIST_PLAN.md, Phase 2. POSTs {@code {baseUrl}/chat/completions} with a
 * system+user message pair, optionally requesting JSON-object response mode,
 * and reads {@code choices[0].message.content}.
 */
@Component
public class OpenAiCompatibleChatClient implements AiChatClient {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiCompatibleChatClient.class);

    private final RestClient aiRestClient;

    public OpenAiCompatibleChatClient(RestClient aiRestClient) {
        this.aiRestClient = aiRestClient;
    }

    @Override
    public AiChatResult complete(AiChatCall call) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", call.model());
        body.put("messages", List.of(
                Map.of("role", "system", "content", call.systemPrompt()),
                Map.of("role", "user", "content", call.userPrompt())));
        if (call.jsonMode()) {
            body.put("response_format", Map.of("type", "json_object"));
        }

        JsonNode response;
        try {
            response = aiRestClient.post()
                    .uri(URI.create(join(call.baseUrl(), "/chat/completions")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        if (call.apiKey() != null && !call.apiKey().isBlank()) {
                            h.setBearerAuth(call.apiKey());
                        }
                    })
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException http) {
            // Upstream returned a non-2xx. The body usually carries the provider's
            // own error detail (e.g. OpenAI's rate-limit / quota / model message),
            // which is the single most useful thing to see for an intermittent 502.
            logger.warn("AI chat call failed: endpoint={} model={} -> HTTP {} body={}",
                    call.baseUrl(), call.model(), http.getStatusCode().value(),
                    AiChatClient.truncate(http.getResponseBodyAsString()));
            throw AiChatClient.mapError(http);
        } catch (RestClientException e) {
            // Connect/read timeout, DNS, connection reset, etc.
            logger.warn("AI chat call failed: endpoint={} model={} -> {}",
                    call.baseUrl(), call.model(), e.toString());
            throw AiChatClient.mapError(e);
        }

        String content = (response == null) ? null
                : response.path("choices").path(0).path("message").path("content").asText(null);
        if (content == null || content.isBlank()) {
            String finishReason = (response == null) ? null
                    : response.path("choices").path(0).path("finish_reason").asText(null);
            logger.warn("AI chat returned an empty completion: endpoint={} model={} finish_reason={}",
                    call.baseUrl(), call.model(), finishReason);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The AI provider returned an empty completion.");
        }

        // OpenAI-compatible usage block: {prompt_tokens, completion_tokens, total_tokens}.
        // Some Custom/Ollama servers omit it — leave the figures null in that case.
        JsonNode usage = response.path("usage");
        return new AiChatResult(content,
                intOrNull(usage, "prompt_tokens"),
                intOrNull(usage, "completion_tokens"),
                intOrNull(usage, "total_tokens"));
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNumber() ? v.asInt() : null;
    }

    private static String join(String base, String path) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b + path;
    }
}
