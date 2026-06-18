package com.pjr22.tripweather.model;

/**
 * The kind of AI provider a {@link AiProviderConfig} talks to. Stored as the
 * enum name (a {@code VARCHAR}) in {@code ai_provider_configs.provider}.
 * AI_ASSIST_PLAN.md, Phase 1.
 *
 * <ul>
 *   <li>{@link #OPENAI} — OpenAI's hosted API. API key required; base URL is the
 *       server default ({@code trip.ai.openai-base-url}).</li>
 *   <li>{@link #ANTHROPIC} — Anthropic Messages API. API key required; base URL
 *       is the server default ({@code trip.ai.anthropic-base-url}).</li>
 *   <li>{@link #CUSTOM} — any OpenAI-compatible endpoint. The user supplies the
 *       base URL (subject to the SSRF guard) and an optional API key.</li>
 *   <li>{@link #OLLAMA} — a local/remote Ollama instance. Offered only when the
 *       operator has set {@code trip.ai.ollama-url}; the endpoint always comes
 *       from that operator config, never from per-config input. API key
 *       optional.</li>
 * </ul>
 */
public enum AiProvider {
    OPENAI,
    ANTHROPIC,
    CUSTOM,
    OLLAMA
}
