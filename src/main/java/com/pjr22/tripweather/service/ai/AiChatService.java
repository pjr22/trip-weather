package com.pjr22.tripweather.service.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.pjr22.tripweather.model.AiProvider;

/**
 * Dispatches a chat completion to the right {@link AiChatClient} for a provider,
 * resolving the base URL from config (or the config's own base URL for Custom,
 * via the SSRF guard) and choosing whether to request JSON-object mode.
 * AI_ASSIST_PLAN.md, Phase 2.
 */
@Service
public class AiChatService {

    private final OpenAiCompatibleChatClient openAiClient;
    private final AnthropicChatClient anthropicClient;
    private final OutboundUrlGuard urlGuard;

    private final String openaiBaseUrl;
    private final String anthropicBaseUrl;
    private final String ollamaUrl;

    public AiChatService(OpenAiCompatibleChatClient openAiClient,
                         AnthropicChatClient anthropicClient,
                         OutboundUrlGuard urlGuard,
                         @Value("${trip.ai.openai-base-url:https://api.openai.com/v1}") String openaiBaseUrl,
                         @Value("${trip.ai.anthropic-base-url:https://api.anthropic.com}") String anthropicBaseUrl,
                         @Value("${trip.ai.ollama-url:}") String ollamaUrl) {
        this.openAiClient = openAiClient;
        this.anthropicClient = anthropicClient;
        this.urlGuard = urlGuard;
        this.openaiBaseUrl = openaiBaseUrl;
        this.anthropicBaseUrl = anthropicBaseUrl;
        this.ollamaUrl = ollamaUrl == null ? "" : ollamaUrl.trim();
    }

    /**
     * Run a completion against the given provider/model.
     *
     * @param customBaseUrl the config's base URL — used (and SSRF-guarded) only
     *                      for {@link AiProvider#CUSTOM}; ignored otherwise.
     * @return the model's text content
     */
    public String complete(AiProvider provider, String model, String apiKey, String customBaseUrl,
                           String systemPrompt, String userPrompt) {
        return switch (provider) {
            case OPENAI -> openAiClient.complete(new AiChatCall(
                    openaiBaseUrl, model, apiKey, systemPrompt, userPrompt, true));
            case OLLAMA -> openAiClient.complete(new AiChatCall(
                    join(ollamaUrl, "/v1"), model, apiKey, systemPrompt, userPrompt, true));
            case CUSTOM -> {
                urlGuard.validate(customBaseUrl);
                // Custom endpoints may not support response_format; rely on the
                // prompt + tolerant parsing instead of risking a 400.
                yield openAiClient.complete(new AiChatCall(
                        customBaseUrl, model, apiKey, systemPrompt, userPrompt, false));
            }
            case ANTHROPIC -> anthropicClient.complete(new AiChatCall(
                    anthropicBaseUrl, model, apiKey, systemPrompt, userPrompt, false));
        };
    }

    private static String join(String base, String path) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b + path;
    }
}
