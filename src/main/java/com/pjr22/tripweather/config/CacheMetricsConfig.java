package com.pjr22.tripweather.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.pjr22.tripweather.service.CachedForecast;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the in-process Caffeine caches to the global {@link MeterRegistry}
 * so the admin Metrics tab can report hit/miss/hit-ratio per cache.
 * Phase 3 of ADMIN_CONSOLE.md (step 2).
 *
 * <p>The binder emits standard Micrometer cache meters under
 * {@code cache.gets{cache=<name>,result=hit|miss}}, which
 * {@link com.pjr22.tripweather.service.MetricsSnapshotService} reads back
 * without caring which kind of cache produced them — DB-backed caches
 * registered in step 3 emit the same name with their own cache tag values.
 *
 * <p>The {@link CacheMeterNames} constants are the single source of truth
 * for {@code cache} tag values; the snapshot service, the binders here,
 * and the DB-cache counters (step 3) all reference the same names.
 *
 * <p>Side-effect-in-constructor pattern: registering Micrometer binders
 * doesn't produce a bean of its own. Constructing this config eagerly
 * (Spring creates @Configuration beans at startup) calls
 * {@code CaffeineCacheMetrics.monitor} for each cache exactly once.
 */
@Configuration
public class CacheMetricsConfig {

    public CacheMetricsConfig(MeterRegistry registry,
                              Cache<String, CachedForecast> forecastCache,
                              Cache<String, JsonNode> forwardGeocodeCache) {
        CaffeineCacheMetrics.monitor(registry, forecastCache,
                CacheMeterNames.FORECAST);
        CaffeineCacheMetrics.monitor(registry, forwardGeocodeCache,
                CacheMeterNames.FORWARD_GEOCODE);
    }

    /**
     * Cache-name constants used as the {@code cache} tag value on
     * {@code cache.gets} meters. Sorted by panel display order. The
     * frontend keys cache rows by these names.
     */
    public static final class CacheMeterNames {
        /** In-memory Caffeine — api.weather.gov forecasts. */
        public static final String FORECAST = "forecast";
        /** In-memory Caffeine — Geoapify forward (search-box) geocodes. */
        public static final String FORWARD_GEOCODE = "geocode-forward";
        /** DB-backed {@code ors_response_cache} rows, {@code endpoint=directions}. */
        public static final String ORS_DIRECTIONS = "ors-directions";
        /** DB-backed {@code ors_response_cache} rows, {@code endpoint=snap}. */
        public static final String ORS_SNAP = "ors-snap";
        /** DB-backed {@code ors_response_cache} rows, {@code endpoint=elevation}. */
        public static final String ORS_ELEVATION = "ors-elevation";
        /** DB-backed {@code ors_response_cache} rows,
         *  {@code endpoint=elevation_lookup} (tiny self-loop used to read elevation
         *  from the local ORS graph). Public-ORS deployments may never see this. */
        public static final String ORS_ELEVATION_LOOKUP = "ors-elevation-lookup";
        /** DB-backed {@code geocode_reverse_cache}. */
        public static final String REVERSE_GEOCODE = "geocode-reverse";
        /** DB-backed {@code nws_gridpoints}. */
        public static final String NWS_GRIDPOINTS = "nws-gridpoints";

        private CacheMeterNames() { }
    }
}
