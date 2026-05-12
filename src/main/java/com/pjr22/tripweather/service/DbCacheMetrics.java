package com.pjr22.tripweather.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Hand-rolled cache hit/miss counters for DB-backed caches
 * ({@code ors_response_cache}, {@code geocode_reverse_cache},
 * {@code nws_gridpoints}). Phase 3 of ADMIN_CONSOLE.md (step 3).
 *
 * <p>Emits {@code cache.gets{cache=<name>,result=hit|miss}} — the same
 * meter name and tag layout that
 * {@link io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics}
 * registers for the in-memory caches. Letting the snapshot service read
 * one meter name across both flavours keeps the cache panel logic flat.
 *
 * <p>Hit semantics: any code path that returns a value backed by the
 * cache row (fresh or stale-on-error) counts as a hit. Any code path
 * that talks to the upstream API counts as a miss. Tracking stale-served
 * separately is outside this phase's scope — surfacing it would require
 * a new tag value and a frontend column.
 */
@Component
public class DbCacheMetrics {

    private final MeterRegistry registry;

    public DbCacheMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordHit(String cacheName) {
        Counter.builder("cache.gets")
                .tag("cache", cacheName)
                .tag("result", "hit")
                .register(registry)
                .increment();
    }

    public void recordMiss(String cacheName) {
        Counter.builder("cache.gets")
                .tag("cache", cacheName)
                .tag("result", "miss")
                .register(registry)
                .increment();
    }
}
