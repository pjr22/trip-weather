package com.pjr22.tripweather.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pjr22.tripweather.service.CachedForecast;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * In-memory cache of api.weather.gov gridpoint forecast responses, keyed by
 * the forecast URL. Per-entry freshness and stale-on-error are decided in
 * {@link com.pjr22.tripweather.service.WeatherService} from each entry's
 * metadata; Caffeine's expireAfterWrite is the outer safety bound that
 * matches the stale-max window — beyond it, no point keeping the entry.
 */
@Configuration
public class WeatherCacheConfig {

    @Bean
    public Cache<String, CachedForecast> forecastCache(
            @Value("${trip.weather.forecast-cache-max:10000}") long maxEntries,
            @Value("${trip.weather.forecast-stale-max-hours:6}") long staleMaxHours) {
        // recordStats() is required for the Caffeine→Micrometer binder
        // registered in CacheMetricsConfig to report non-zero hit/miss
        // counters on the admin Metrics tab. Cheap (Caffeine keeps four
        // longs per cache); not visible anywhere else.
        return Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterWrite(Duration.ofHours(staleMaxHours))
                .recordStats()
                .build();
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
