package com.pjr22.tripweather.service;

import com.pjr22.tripweather.config.CacheMetricsConfig.CacheMeterNames;
import com.pjr22.tripweather.dto.MetricsSnapshotDto;
import com.pjr22.tripweather.dto.MetricsSnapshotDto.CacheStats;
import com.pjr22.tripweather.dto.MetricsSnapshotDto.Heap;
import com.pjr22.tripweather.dto.MetricsSnapshotDto.HttpLatency;
import com.pjr22.tripweather.dto.MetricsSnapshotDto.Routing;
import com.pjr22.tripweather.dto.MetricsSnapshotDto.Routing.EndpointRow;
import com.pjr22.tripweather.dto.MetricsSnapshotDto.TopUri;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.search.Search;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Live read of {@link MeterRegistry} into a flat JSON snapshot for the
 * admin console's Metrics tab. Phase 3 of ADMIN_CONSOLE.md. No
 * persistence; every call reads the current meter values.
 *
 * <p>Per-panel design notes:
 *
 * <ul>
 *   <li><b>HTTP latency</b> — aggregates every {@code http.server.requests}
 *       Timer (one per URI × method × status combination) into one mean /
 *       p50 / p95 / p99 / max. Percentile values come from each Timer's
 *       {@link io.micrometer.core.instrument.distribution.HistogramSnapshot#percentileValues()},
 *       which is only populated when
 *       {@code management.metrics.distribution.percentiles.http.server.requests=0.5,0.95,0.99}
 *       is set in {@code application.properties}. The separate
 *       {@code percentiles-histogram.*} flag publishes bucket time-series
 *       for Prometheus client-side quantile computation, but does <em>not</em>
 *       populate {@code percentileValues()} — without {@code percentiles.*}
 *       the snapshot reports zeros even with histogram buckets enabled.</li>
 *   <li><b>Routing dispatch</b> — reads the three {@link
 *       com.pjr22.tripweather.routing.RoutingMetrics} counter families
 *       ({@code trip.routing.local.success}, {@code .local.fallback},
 *       {@code .public.calls}), groups by {@code endpoint}, and breaks
 *       the fallback count down by {@code reason}.</li>
 *   <li><b>JVM heap</b> — {@code jvm.memory.used{area=heap}} and
 *       {@code jvm.memory.max{area=heap}} summed across heap pools. A
 *       hard {@code -Xmx} cap shows as a finite {@code max}; without one
 *       the JVM may report {@code -1} per pool, which we surface as
 *       {@code max=0} and {@code usedPct=null}.</li>
 *   <li><b>Top URIs</b> — five highest-count {@code http.server.requests}
 *       rows. Useful for spotting the dominant path under load.</li>
 *   <li><b>Caches</b> — populated by steps 2 and 3 of phase 3. Empty
 *       list in this commit; the list is the wire format and the panel
 *       just shows "no caches reporting" until the binders ship.</li>
 * </ul>
 */
@Service
public class MetricsSnapshotService {

    private static final String HTTP_TIMER = "http.server.requests";

    private static final String ROUTING_LOCAL_SUCCESS = "trip.routing.local.success";
    private static final String ROUTING_LOCAL_FALLBACK = "trip.routing.local.fallback";
    private static final String ROUTING_PUBLIC_CALLS = "trip.routing.public.calls";
    private static final String TAG_ENDPOINT = "endpoint";
    private static final String TAG_REASON = "reason";

    private static final String JVM_MEMORY_USED = "jvm.memory.used";
    private static final String JVM_MEMORY_MAX = "jvm.memory.max";
    private static final String TAG_AREA = "area";
    private static final String AREA_HEAP = "heap";

    private static final String CACHE_GETS = "cache.gets";
    private static final String TAG_CACHE = "cache";
    private static final String TAG_RESULT = "result";
    private static final String RESULT_HIT = "hit";
    private static final String RESULT_MISS = "miss";

    /**
     * Ordered list of (cacheName, kind) pairs that drives the cache panel.
     * Caches missing from the registry simply don't appear; the order here
     * is the order operators see in the UI. {@code kind} is a UI hint
     * ({@code "caffeine"} = in-memory, {@code "db"} = JPA-backed). The
     * service doesn't enforce that — a cache name with no meter rows is
     * silently skipped.
     */
    private static final List<String[]> CACHE_PANEL_ORDER = List.of(
            new String[] {CacheMeterNames.FORECAST,              "caffeine"},
            new String[] {CacheMeterNames.FORWARD_GEOCODE,       "caffeine"},
            new String[] {CacheMeterNames.REVERSE_GEOCODE,       "db"},
            new String[] {CacheMeterNames.ORS_DIRECTIONS,        "db"},
            new String[] {CacheMeterNames.ORS_SNAP,              "db"},
            new String[] {CacheMeterNames.ORS_ELEVATION,         "db"},
            new String[] {CacheMeterNames.ORS_ELEVATION_LOOKUP,  "db"},
            new String[] {CacheMeterNames.NWS_GRIDPOINTS,        "db"});

    private static final int TOP_URI_LIMIT = 5;

    private final MeterRegistry registry;

    public MetricsSnapshotService(MeterRegistry registry) {
        this.registry = registry;
    }

    public MetricsSnapshotDto snapshot() {
        return new MetricsSnapshotDto(
                httpLatency(),
                routing(),
                jvmHeap(),
                topUris(),
                caches());
    }

    private HttpLatency httpLatency() {
        Collection<Timer> timers = Search.in(registry).name(HTTP_TIMER).timers();
        if (timers.isEmpty()) {
            return new HttpLatency(0, 0, 0, 0, 0, 0);
        }
        long count = 0;
        double totalMs = 0;
        double maxMs = 0;
        // Percentiles need to be aggregated from each Timer's HistogramSnapshot,
        // not naively averaged. We compute a request-count-weighted average per
        // percentile across all URI timers — accurate to within bucket width of
        // a true global percentile and far cheaper than a streaming merge.
        double p50Weighted = 0;
        double p95Weighted = 0;
        double p99Weighted = 0;
        long weightTotal = 0;
        for (Timer timer : timers) {
            long timerCount = timer.count();
            count += timerCount;
            totalMs += timer.totalTime(TimeUnit.MILLISECONDS);
            maxMs = Math.max(maxMs, timer.max(TimeUnit.MILLISECONDS));
            if (timerCount > 0) {
                p50Weighted += timerCount * percentileMs(timer, 0.50);
                p95Weighted += timerCount * percentileMs(timer, 0.95);
                p99Weighted += timerCount * percentileMs(timer, 0.99);
                weightTotal += timerCount;
            }
        }
        double meanMs = count == 0 ? 0 : totalMs / count;
        double p50 = weightTotal == 0 ? 0 : p50Weighted / weightTotal;
        double p95 = weightTotal == 0 ? 0 : p95Weighted / weightTotal;
        double p99 = weightTotal == 0 ? 0 : p99Weighted / weightTotal;
        return new HttpLatency(count, meanMs, p50, p95, p99, maxMs);
    }

    private double percentileMs(Timer timer, double quantile) {
        var histogram = timer.takeSnapshot();
        for (var bucket : histogram.percentileValues()) {
            if (Math.abs(bucket.percentile() - quantile) < 1e-9) {
                double nanos = bucket.value();
                return Double.isNaN(nanos) ? 0 : nanos / 1_000_000d;
            }
        }
        return 0;
    }

    private Routing routing() {
        // Aggregate by endpoint. Counter names are stable; tags may not all
        // exist if the registry has never seen a fallback / public call.
        Map<String, EndpointAgg> byEndpoint = new LinkedHashMap<>();

        for (Counter c : Search.in(registry).name(ROUTING_LOCAL_SUCCESS).counters()) {
            agg(byEndpoint, tagValue(c, TAG_ENDPOINT)).localSuccess += (long) c.count();
        }
        for (Counter c : Search.in(registry).name(ROUTING_PUBLIC_CALLS).counters()) {
            agg(byEndpoint, tagValue(c, TAG_ENDPOINT)).publicCalls += (long) c.count();
        }
        for (Counter c : Search.in(registry).name(ROUTING_LOCAL_FALLBACK).counters()) {
            String endpoint = tagValue(c, TAG_ENDPOINT);
            String reason = tagValue(c, TAG_REASON);
            EndpointAgg a = agg(byEndpoint, endpoint);
            long n = (long) c.count();
            a.fallbackTotal += n;
            a.fallbackByReason.merge(reason, n, Long::sum);
        }

        List<EndpointRow> rows = new ArrayList<>(byEndpoint.size());
        for (Map.Entry<String, EndpointAgg> e : byEndpoint.entrySet()) {
            EndpointAgg a = e.getValue();
            rows.add(new EndpointRow(
                    e.getKey(),
                    a.localSuccess,
                    a.publicCalls,
                    a.fallbackTotal,
                    Collections.unmodifiableMap(a.fallbackByReason)));
        }
        return new Routing(rows);
    }

    private EndpointAgg agg(Map<String, EndpointAgg> map, String endpoint) {
        return map.computeIfAbsent(endpoint == null ? "unknown" : endpoint,
                k -> new EndpointAgg());
    }

    private static String tagValue(Meter meter, String key) {
        for (Tag t : meter.getId().getTagsAsIterable()) {
            if (t.getKey().equals(key)) {
                return t.getValue();
            }
        }
        return null;
    }

    private Heap jvmHeap() {
        long used = sumGauges(JVM_MEMORY_USED, AREA_HEAP);
        long max = sumGauges(JVM_MEMORY_MAX, AREA_HEAP);
        // A pool reporting -1 for max means "unbounded" — surface as 0 so the
        // frontend can show "—" instead of a misleading negative percentage.
        long maxOrZero = max < 0 ? 0 : max;
        Double pct = maxOrZero == 0 ? null : (used * 100.0) / maxOrZero;
        return new Heap(used, maxOrZero, pct);
    }

    private long sumGauges(String name, String areaTag) {
        long sum = 0;
        for (Gauge g : Search.in(registry).name(name).tag(TAG_AREA, areaTag).gauges()) {
            double v = g.value();
            if (Double.isFinite(v) && v > 0) {
                sum += (long) v;
            }
        }
        return sum;
    }

    private List<TopUri> topUris() {
        Collection<Timer> timers = Search.in(registry).name(HTTP_TIMER).timers();
        List<TopUri> rows = new ArrayList<>(timers.size());
        for (Timer t : timers) {
            long count = t.count();
            if (count == 0) {
                continue;
            }
            double totalMs = t.totalTime(TimeUnit.MILLISECONDS);
            rows.add(new TopUri(
                    tagOrEmpty(t, "uri"),
                    tagOrEmpty(t, "method"),
                    tagOrEmpty(t, "status"),
                    count,
                    totalMs / count,
                    t.max(TimeUnit.MILLISECONDS)));
        }
        rows.sort(Comparator.comparingLong(TopUri::count).reversed());
        return rows.size() <= TOP_URI_LIMIT ? rows : rows.subList(0, TOP_URI_LIMIT);
    }

    private static String tagOrEmpty(Meter m, String key) {
        String v = tagValue(m, key);
        return v == null ? "" : v;
    }

    /**
     * Cache hit/miss panel. Reads {@code cache.gets{cache=*,result=hit|miss}}
     * meters and groups by the cache tag. Step 2 (Caffeine) and step 3
     * (DB-backed) emit the same meter shape, so this is provider-agnostic.
     *
     * <p>Rows appear in {@link #CACHE_PANEL_ORDER} order. A cache absent
     * from the registry is skipped. A cache present but never accessed
     * (hits + misses == 0) shows {@code hitRatio=null} so the frontend
     * renders "—" rather than 0% or a divide-by-zero artefact.
     */
    private List<CacheStats> caches() {
        // Build a map of cache name → {hits, misses} from the registry once,
        // then walk the canonical order so the wire shape is stable even if
        // future caches register themselves before existing ones.
        Map<String, long[]> byCache = new HashMap<>();
        for (var counter : Search.in(registry).name(CACHE_GETS).functionCounters()) {
            String cacheTag = tagValue(counter, TAG_CACHE);
            String resultTag = tagValue(counter, TAG_RESULT);
            if (cacheTag == null) {
                continue;
            }
            long[] slot = byCache.computeIfAbsent(cacheTag, k -> new long[2]);
            long value = (long) counter.count();
            if (RESULT_HIT.equals(resultTag)) {
                slot[0] += value;
            } else if (RESULT_MISS.equals(resultTag)) {
                slot[1] += value;
            }
        }
        // Caffeine's CacheMeterBinder registers cache.gets as a FunctionCounter
        // (lambda-backed), but plain Counter.increment() emits a regular
        // Counter — read both flavours so step 3's hand-rolled counters appear
        // in the same panel as step 2's Caffeine binders.
        for (var counter : Search.in(registry).name(CACHE_GETS).counters()) {
            String cacheTag = tagValue(counter, TAG_CACHE);
            String resultTag = tagValue(counter, TAG_RESULT);
            if (cacheTag == null) {
                continue;
            }
            long[] slot = byCache.computeIfAbsent(cacheTag, k -> new long[2]);
            long value = (long) counter.count();
            if (RESULT_HIT.equals(resultTag)) {
                slot[0] += value;
            } else if (RESULT_MISS.equals(resultTag)) {
                slot[1] += value;
            }
        }

        List<CacheStats> rows = new ArrayList<>();
        for (String[] entry : CACHE_PANEL_ORDER) {
            String name = entry[0];
            String kind = entry[1];
            long[] slot = byCache.get(name);
            if (slot == null) {
                continue;
            }
            long hits = slot[0];
            long misses = slot[1];
            Double hitRatio = (hits + misses == 0) ? null
                    : (hits * 100.0) / (hits + misses);
            rows.add(new CacheStats(name, kind, hits, misses, hitRatio));
        }
        return rows;
    }

    private static final class EndpointAgg {
        long localSuccess;
        long publicCalls;
        long fallbackTotal;
        final Map<String, Long> fallbackByReason = new HashMap<>();
    }
}
