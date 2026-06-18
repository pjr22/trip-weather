package com.pjr22.tripweather.service.ai;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
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

    private final RestClient aiRestClient;

    public OpenAiCompatibleChatClient(RestClient aiRestClient) {
        this.aiRestClient = aiRestClient;
    }

    @Override
    public String complete(AiChatCall call) {
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
        } catch (RestClientException e) {
            throw AiChatClient.mapError(e);
        }

        String content = (response == null) ? null
                : response.path("choices").path(0).path("message").path("content").asText(null);
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The AI provider returned an empty completion.");
        }
        return content;
    }

    private static String join(String base, String path) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b + path;
    }
}
