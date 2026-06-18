package com.pjr22.tripweather.service.ai;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tolerantly parses the AI's response text into a list of {@link AiLocation}.
 * AI_ASSIST_PLAN.md, Phase 2.
 *
 * <p>Models often wrap JSON in prose or markdown fences even when told not to,
 * so the parser strips ``` fences and, failing a direct parse, extracts the
 * substring from the first {@code &#123;} to the last {@code &#125;} and parses
 * that. A failure throws {@link LocationParseException}; the orchestration does
 * one repair re-ask before giving up.
 */
@Component
public class LocationListParser {

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LocationsHolder(List<AiLocation> locations) {
    }

    private final ObjectMapper objectMapper;

    public LocationListParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parse the model output into locations (skipping any with a blank name).
     *
     * @throws LocationParseException if no parseable JSON object is found
     */
    public List<AiLocation> parse(String modelText) {
        if (modelText == null || modelText.isBlank()) {
            throw new LocationParseException("model returned no text");
        }
        String json = extractJsonObject(stripFences(modelText));
        LocationsHolder holder;
        try {
            holder = objectMapper.readValue(json, LocationsHolder.class);
        } catch (Exception e) {
            throw new LocationParseException("could not parse JSON from model output", e);
        }
        if (holder == null || holder.locations() == null) {
            throw new LocationParseException("model output had no \"locations\" array");
        }
        List<AiLocation> out = new ArrayList<>();
        for (AiLocation loc : holder.locations()) {
            if (loc != null && loc.name() != null && !loc.name().isBlank()) {
                out.add(loc);
            }
        }
        return out;
    }

    /** Strip a leading/trailing markdown code fence if present. */
    private static String stripFences(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline >= 0) {
                t = t.substring(firstNewline + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
        }
        return t.trim();
    }

    /**
     * Return the trimmed text if it already looks like a JSON object, otherwise
     * the substring spanning the first {@code &#123;} to the last {@code &#125;}.
     * Throws if there's no brace pair at all.
     */
    private static String extractJsonObject(String text) {
        String t = text.trim();
        if (t.startsWith("{") && t.endsWith("}")) {
            return t;
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new LocationParseException("no JSON object found in model output");
        }
        return t.substring(start, end + 1);
    }

    /** Thrown when the model output can't be parsed into the locations shape. */
    public static class LocationParseException extends RuntimeException {
        public LocationParseException(String message) {
            super(message);
        }

        public LocationParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
