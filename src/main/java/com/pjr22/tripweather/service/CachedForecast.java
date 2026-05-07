package com.pjr22.tripweather.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Cached gridpoint forecast response. {@code freshUntil} is anchored to the
 * NWS-published {@code updateTime} when present, otherwise to a TTL after
 * {@code fetchedAt}; {@code fetchedAt} is what stale-on-error checks against.
 */
public record CachedForecast(JsonNode data, Instant freshUntil, Instant fetchedAt) {}
