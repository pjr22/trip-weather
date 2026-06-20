package com.pjr22.tripweather.service.ai;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
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

    private static final Logger logger = LoggerFactory.getLogger(AnthropicChatClient.class);

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
    public AiChatResult complete(AiChatCall call) {
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
        } catch (RestClientResponseException http) {
            logger.warn("AI chat call failed: endpoint={} model={} -> HTTP {} body={}",
                    call.baseUrl(), call.model(), http.getStatusCode().value(),
                    AiChatClient.truncate(http.getResponseBodyAsString()));
            throw AiChatClient.mapError(http);
        } catch (RestClientException e) {
            logger.warn("AI chat call failed: endpoint={} model={} -> {}",
                    call.baseUrl(), call.model(), e.toString());
            throw AiChatClient.mapError(e);
        }

        String content = (response == null) ? null
                : response.path("content").path(0).path("text").asText(null);
        if (content == null || content.isBlank()) {
            String stopReason = (response == null) ? null
                    : response.path("stop_reason").asText(null);
            logger.warn("AI chat returned an empty completion: endpoint={} model={} stop_reason={}",
                    call.baseUrl(), call.model(), stopReason);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The AI provider returned an empty completion.");
        }

        // Anthropic usage block: {input_tokens, output_tokens}. Map to the same
        // prompt/completion/total shape; total is the sum when both are present.
        JsonNode usage = response.path("usage");
        Integer inputTokens = intOrNull(usage, "input_tokens");
        Integer outputTokens = intOrNull(usage, "output_tokens");
        Integer totalTokens = (inputTokens != null && outputTokens != null)
                ? inputTokens + outputTokens : null;
        return new AiChatResult(content, inputTokens, outputTokens, totalTokens);
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
