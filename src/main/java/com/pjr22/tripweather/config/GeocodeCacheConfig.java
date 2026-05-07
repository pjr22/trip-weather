package com.pjr22.tripweather.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * In-memory cache of Geoapify /geocode/search responses, keyed by the
 * lowercased + trimmed query string. Forward geocoding is low volume and
 * results are nice-to-have, so straight TTL eviction with no stale-on-error.
 */
@Configuration
public class GeocodeCacheConfig {

    @Bean
    public Cache<String, JsonNode> forwardGeocodeCache(
            @Value("${trip.geocode.forward-cache-max:1000}") long maxEntries,
            @Value("${trip.geocode.forward-cache-ttl-hours:24}") long ttlHours) {
        return Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterWrite(Duration.ofHours(ttlHours))
                .build();
    }
}
