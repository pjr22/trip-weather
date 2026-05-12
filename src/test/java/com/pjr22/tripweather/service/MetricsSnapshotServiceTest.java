package com.pjr22.tripweather.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pjr22.tripweather.config.CacheMetricsConfig.CacheMeterNames;
import com.pjr22.tripweather.dto.MetricsSnapshotDto;
import com.pjr22.tripweather.dto.MetricsSnapshotDto.CacheStats;
import com.pjr22.tripweather.dto.MetricsSnapshotDto.Routing.EndpointRow;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link MetricsSnapshotService}. Verifies the panel shape
 * against a freshly-constructed {@link SimpleMeterRegistry} — known meters
 * are populated and read back; unknown meter families return zeros, not
 * exceptions; divide-by-zero on an unbounded heap is handled.
 */
class MetricsSnapshotServiceTest {

    @Test
    void emptyRegistryReturnsZerosNotExceptions() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MetricsSnapshotService svc = new MetricsSnapshotService(registry);

        MetricsSnapshotDto snap = svc.snapshot();

        assertThat(snap.http()).isNotNull();
        assertThat(snap.http().count()).isZero();
        assertThat(snap.http().meanMs()).isZero();
        assertThat(snap.http().p95Ms()).isZero();
        assertThat(snap.http().maxMs()).isZero();
        assertThat(snap.routing().endpoints()).isEmpty();
        assertThat(snap.jvmHeap().usedBytes()).isZero();
        assertThat(snap.jvmHeap().maxBytes()).isZero();
        assertThat(snap.jvmHeap().usedPct()).isNull();
        assertThat(snap.topUris()).isEmpty();
        assertThat(snap.caches()).isEmpty();
    }

    @Test
    void httpTimerAggregatesAcrossUris() {
        MeterRegistry registry = new SimpleMeterRegistry();
        // Two URIs, different request volumes. Without a percentile histogram
        // on the underlying Timer, percentile fields stay 0 — this test
        // exercises count/totalTime/max aggregation, which is the path that
        // the panel relies on for the "count" and "mean" displays.
        Timer.builder("http.server.requests")
                .tags(Tags.of("uri", "/api/route", "method", "POST", "status", "200"))
                .register(registry)
                .record(Duration.ofMillis(100));
        Timer t2 = Timer.builder("http.server.requests")
                .tags(Tags.of("uri", "/api/weather", "method", "GET", "status", "200"))
                .register(registry);
        t2.record(Duration.ofMillis(20));
        t2.record(Duration.ofMillis(40));
        t2.record(Duration.ofMillis(60));

        MetricsSnapshotService svc = new MetricsSnapshotService(registry);
        MetricsSnapshotDto snap = svc.snapshot();

        assertThat(snap.http().count()).isEqualTo(4);
        // (100 + 20 + 40 + 60) / 4 = 55ms
        assertThat(snap.http().meanMs()).isCloseTo(55.0, within(0.5));
        // Max is the largest single recorded duration.
        assertThat(snap.http().maxMs()).isCloseTo(100.0, within(1.0));
    }

    @Test
    void topUrisRankedByCountAndCappedAtFive() {
        MeterRegistry registry = new SimpleMeterRegistry();
        recordTimer(registry, "/api/a", 1);
        recordTimer(registry, "/api/b", 10);
        recordTimer(registry, "/api/c", 5);
        recordTimer(registry, "/api/d", 7);
        recordTimer(registry, "/api/e", 3);
        recordTimer(registry, "/api/f", 9);
        recordTimer(registry, "/api/g", 2);

        MetricsSnapshotService svc = new MetricsSnapshotService(registry);
        MetricsSnapshotDto snap = svc.snapshot();

        assertThat(snap.topUris()).hasSize(5);
        assertThat(snap.topUris().get(0).uri()).isEqualTo("/api/b");
        assertThat(snap.topUris().get(0).count()).isEqualTo(10);
        assertThat(snap.topUris().get(1).uri()).isEqualTo("/api/f");
        assertThat(snap.topUris().get(2).uri()).isEqualTo("/api/d");
        // The two lowest-count URIs (/api/a=1, /api/g=2) must be excluded.
        assertThat(snap.topUris()).noneMatch(r -> r.uri().equals("/api/a"));
        assertThat(snap.topUris()).noneMatch(r -> r.uri().equals("/api/g"));
    }

    private static void recordTimer(MeterRegistry registry, String uri, int hits) {
        Timer t = Timer.builder("http.server.requests")
                .tags(Tags.of("uri", uri, "method", "GET", "status", "200"))
                .register(registry);
        for (int i = 0; i < hits; i++) {
            t.record(Duration.ofMillis(50));
        }
    }

    @Test
    void routingPanelAggregatesSuccessFallbackPublicByEndpoint() {
        MeterRegistry registry = new SimpleMeterRegistry();
        Counter.builder("trip.routing.local.success")
                .tag("endpoint", "directions").register(registry).increment(7);
        Counter.builder("trip.routing.public.calls")
                .tag("endpoint", "directions").register(registry).increment(3);
        // Two fallback rows on the same endpoint with different reasons —
        // the panel must show fallbackTotal = 5 and the per-reason map.
        Counter.builder("trip.routing.local.fallback")
                .tag("endpoint", "directions").tag("reason", "out_of_coverage")
                .register(registry).increment(4);
        Counter.builder("trip.routing.local.fallback")
                .tag("endpoint", "directions").tag("reason", "timeout")
                .register(registry).increment(1);

        // A second endpoint with only a public call — confirms that endpoints
        // missing some counter families still appear in the rows.
        Counter.builder("trip.routing.public.calls")
                .tag("endpoint", "snap").register(registry).increment(2);

        MetricsSnapshotService svc = new MetricsSnapshotService(registry);
        MetricsSnapshotDto snap = svc.snapshot();

        assertThat(snap.routing().endpoints()).hasSize(2);
        EndpointRow directions = snap.routing().endpoints().stream()
                .filter(r -> r.endpoint().equals("directions"))
                .findFirst().orElseThrow();
        assertThat(directions.localSuccess()).isEqualTo(7);
        assertThat(directions.publicCalls()).isEqualTo(3);
        assertThat(directions.fallbackTotal()).isEqualTo(5);
        assertThat(directions.fallbackByReason())
                .containsEntry("out_of_coverage", 4L)
                .containsEntry("timeout", 1L);

        EndpointRow snap2 = snap.routing().endpoints().stream()
                .filter(r -> r.endpoint().equals("snap"))
                .findFirst().orElseThrow();
        assertThat(snap2.localSuccess()).isZero();
        assertThat(snap2.publicCalls()).isEqualTo(2);
        assertThat(snap2.fallbackTotal()).isZero();
        assertThat(snap2.fallbackByReason()).isEmpty();
    }

    @Test
    void jvmHeapSumsAcrossHeapPoolsAndComputesPercent() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AtomicLong edenUsed = new AtomicLong(40_000_000L);
        AtomicLong oldUsed  = new AtomicLong(60_000_000L);
        AtomicLong edenMax  = new AtomicLong(100_000_000L);
        AtomicLong oldMax   = new AtomicLong(300_000_000L);
        gauge(registry, "jvm.memory.used", "Eden", edenUsed);
        gauge(registry, "jvm.memory.used", "Old",  oldUsed);
        gauge(registry, "jvm.memory.max",  "Eden", edenMax);
        gauge(registry, "jvm.memory.max",  "Old",  oldMax);

        MetricsSnapshotService svc = new MetricsSnapshotService(registry);
        MetricsSnapshotDto snap = svc.snapshot();

        assertThat(snap.jvmHeap().usedBytes()).isEqualTo(100_000_000L);
        assertThat(snap.jvmHeap().maxBytes()).isEqualTo(400_000_000L);
        assertThat(snap.jvmHeap().usedPct()).isCloseTo(25.0, within(0.0001));
    }

    @Test
    void unboundedHeapYieldsNullPercent() {
        // A pool reporting -1 for max means the JVM doesn't enforce a cap on
        // that pool. The panel surfaces this as maxBytes=0 + usedPct=null
        // so the frontend can render "—" rather than divide-by-zero or a
        // misleading negative percentage.
        MeterRegistry registry = new SimpleMeterRegistry();
        gauge(registry, "jvm.memory.used", "Eden", new AtomicLong(50_000_000L));
        gauge(registry, "jvm.memory.max",  "Eden", new AtomicLong(-1L));

        MetricsSnapshotService svc = new MetricsSnapshotService(registry);
        MetricsSnapshotDto snap = svc.snapshot();

        assertThat(snap.jvmHeap().usedBytes()).isEqualTo(50_000_000L);
        assertThat(snap.jvmHeap().maxBytes()).isZero();
        assertThat(snap.jvmHeap().usedPct()).isNull();
    }

    private static void gauge(MeterRegistry registry, String name, String pool,
                              AtomicLong supplier) {
        Gauge.builder(name, supplier, AtomicLong::doubleValue)
                .tags(Tags.of("area", "heap", "id", pool))
                .register(registry);
    }

    @Test
    void caffeineBinderPopulatesHitsAndMisses() {
        MeterRegistry registry = new SimpleMeterRegistry();
        // Mirror the dev-time wiring: recordStats() on the cache + the
        // Caffeine→Micrometer binder. Stats only flow when both are present;
        // omitting recordStats() leaves the binder reporting zeros, which
        // would silently make this test useless.
        Cache<String, String> forecast = Caffeine.newBuilder().recordStats().build();
        CaffeineCacheMetrics.monitor(registry, forecast, CacheMeterNames.FORECAST);

        // Three misses, then a hit, then a hit, then a miss: 2 hits + 4 misses.
        forecast.getIfPresent("a"); // miss
        forecast.getIfPresent("b"); // miss
        forecast.put("a", "value");
        forecast.getIfPresent("a"); // hit
        forecast.getIfPresent("a"); // hit
        forecast.getIfPresent("c"); // miss
        forecast.getIfPresent("d"); // miss

        MetricsSnapshotService svc = new MetricsSnapshotService(registry);
        MetricsSnapshotDto snap = svc.snapshot();

        assertThat(snap.caches()).hasSize(1);
        CacheStats row = snap.caches().get(0);
        assertThat(row.name()).isEqualTo(CacheMeterNames.FORECAST);
        assertThat(row.kind()).isEqualTo("caffeine");
        assertThat(row.hits()).isEqualTo(2);
        assertThat(row.misses()).isEqualTo(4);
        assertThat(row.hitRatio()).isCloseTo(2.0 * 100 / 6, within(0.0001));
    }

    @Test
    void neverAccessedCacheReportsNullHitRatio() {
        // Both hit and miss counters report 0 — dividing would NaN/Infinity.
        // The snapshot service must surface null so the frontend can render
        // "—" instead of "NaN%" or "0%" (which would falsely imply "all
        // misses, no hits ever").
        MeterRegistry registry = new SimpleMeterRegistry();
        Cache<String, String> cache = Caffeine.newBuilder().recordStats().build();
        CaffeineCacheMetrics.monitor(registry, cache, CacheMeterNames.FORWARD_GEOCODE);

        MetricsSnapshotService svc = new MetricsSnapshotService(registry);
        MetricsSnapshotDto snap = svc.snapshot();

        assertThat(snap.caches()).hasSize(1);
        CacheStats row = snap.caches().get(0);
        assertThat(row.hits()).isZero();
        assertThat(row.misses()).isZero();
        assertThat(row.hitRatio()).isNull();
    }

    @Test
    void cachesAppearInCanonicalPanelOrder() {
        // Register two Caffeine caches in reverse panel order — the snapshot
        // must still return them in canonical order (forecast first,
        // forward-geocode second) so the frontend's column order is stable.
        MeterRegistry registry = new SimpleMeterRegistry();
        Cache<String, String> geocode = Caffeine.newBuilder().recordStats().build();
        Cache<String, String> forecast = Caffeine.newBuilder().recordStats().build();
        CaffeineCacheMetrics.monitor(registry, geocode, CacheMeterNames.FORWARD_GEOCODE);
        CaffeineCacheMetrics.monitor(registry, forecast, CacheMeterNames.FORECAST);

        MetricsSnapshotService svc = new MetricsSnapshotService(registry);
        MetricsSnapshotDto snap = svc.snapshot();

        assertThat(snap.caches()).extracting(CacheStats::name)
                .containsExactly(CacheMeterNames.FORECAST, CacheMeterNames.FORWARD_GEOCODE);
    }

    @Test
    void plainCounterCachesAreReadAlongsideFunctionCounters() {
        // Step 3 will emit hand-rolled Counters (not FunctionCounters) for
        // the DB-backed caches. The snapshot must read both flavours under
        // the same cache.gets name + cache tag.
        MeterRegistry registry = new SimpleMeterRegistry();
        Counter.builder("cache.gets")
                .tag("cache", CacheMeterNames.ORS_DIRECTIONS)
                .tag("result", "hit").register(registry).increment(8);
        Counter.builder("cache.gets")
                .tag("cache", CacheMeterNames.ORS_DIRECTIONS)
                .tag("result", "miss").register(registry).increment(2);

        MetricsSnapshotService svc = new MetricsSnapshotService(registry);
        MetricsSnapshotDto snap = svc.snapshot();

        CacheStats row = snap.caches().stream()
                .filter(c -> c.name().equals(CacheMeterNames.ORS_DIRECTIONS))
                .findFirst().orElseThrow();
        assertThat(row.hits()).isEqualTo(8);
        assertThat(row.misses()).isEqualTo(2);
        assertThat(row.hitRatio()).isCloseTo(80.0, within(0.0001));
        assertThat(row.kind()).isEqualTo("db");
    }
}
