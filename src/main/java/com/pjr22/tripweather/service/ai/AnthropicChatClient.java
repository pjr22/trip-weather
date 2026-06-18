package com.pjr22.tripweather.service.ai;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Chat client for the Anthropic Messages API. AI_ASSIST_PLAN.md, Phase 2. POSTs
 * {@code {baseUrl}/v1/messages} with the {@code x-api-key} + {@code
 * anthropic-version} headers, a top-level {@code system} string and a single
 * user message, and reads {@code content[0].text}. JSON output is requested via
 * the prompt (Anthropic has no {@code response_format}), so {@link
 * AiChatCall#jsonMode()} is ignored here.
 */
@Component
public class AnthropicChatClient implements AiChatClient {

    /**
     * Output cap. The assistant returns a short JSON list of locations, so a few
     * thousand tokens is ample even for the max-waypoints case.
     */
    private static final int MAX_TOKENS = 4096;

    private final RestClient aiRestClient;
    private final String anthropicVersion;

    public AnthropicChatClient(RestClient aiRestClient,
                               @Value("${trip.ai.anthropic-version:2023-06-01}") String anthropicVersion) {
        this.aiRestClient = aiRestClient;
        this.anthropicVersion = anthropicVersion;
    }

    @Override
    public String complete(AiChatCall call) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", call.model());
        body.put("max_tokens", MAX_TOKENS);
        body.put("system", call.systemPrompt());
        body.put("messages", List.of(
                Map.of("role", "user", "content", call.userPrompt())));

        JsonNode response;
        try {
            response = aiRestClient.post()
                    .uri(URI.create(join(call.baseUrl(), "/v1/messages")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        h.set("x-api-key", call.apiKey());
                        h.set("anthropic-version", anthropicVersion);
                    })
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw AiChatClient.mapError(e);
        }

        String content = (response == null) ? null
                : response.path("content").path(0).path("text").asText(null);
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
