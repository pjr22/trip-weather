package com.pjr22.tripweather.service.ai;

/**
 * The outcome of one chat completion: the model's text plus any token-usage
 * figures the provider reported. AI_ASSIST_PLAN.md, Phase 4 (detail surfacing).
 *
 * <p>Token fields are nullable — not every provider/endpoint returns a usage
 * block (some OpenAI-compatible and Ollama servers omit it), so callers must
 * treat null as "unknown" rather than zero.
 *
 * @param content          the assistant's text content (never null/blank — the
 *                         client throws 502 before constructing this otherwise)
 * @param promptTokens     input/prompt tokens, or null if unreported
 * @param completionTokens output/completion tokens, or null if unreported
 * @param totalTokens      total tokens, or null if unreported
 */
public record AiChatResult(
        String content,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens) {
}
