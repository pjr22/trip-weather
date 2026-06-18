package com.pjr22.tripweather.service.ai;

/**
 * A single, fully-resolved chat completion request to an AI provider.
 * AI_ASSIST_PLAN.md, Phase 2. Built by {@link AiChatService} (which resolves
 * the base URL per provider and applies the SSRF guard for Custom) and consumed
 * by an {@link AiChatClient}.
 *
 * @param baseUrl  provider API root (OpenAI-compatible: includes {@code /v1};
 *                 Anthropic: the host root). The client appends its own path.
 * @param model    model id (e.g. {@code gpt-4o-mini}, {@code claude-opus-4-8}).
 * @param apiKey   plaintext API key, or null for keyless Custom/Ollama.
 * @param systemPrompt system instruction.
 * @param userPrompt   user message.
 * @param jsonMode whether to request the provider's JSON-object response mode
 *                 (OpenAI-compatible {@code response_format}); ignored by
 *                 providers that don't support it.
 */
public record AiChatCall(
        String baseUrl,
        String model,
        String apiKey,
        String systemPrompt,
        String userPrompt,
        boolean jsonMode) {
}
