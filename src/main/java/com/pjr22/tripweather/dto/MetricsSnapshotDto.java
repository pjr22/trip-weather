package com.pjr22.tripweather.dto;

import java.util.List;
import java.util.Map;

/**
 * JSON shape of {@code GET /api/admin/metrics}. Phase 3 of ADMIN_CONSOLE.md.
 *
 * <p>Every panel field is nullable so a registry missing a meter family
 * renders cleanly as "—" rather than failing the whole snapshot. The
 * service ({@link com.pjr22.tripweather.service.MetricsSnapshotService})
 * returns zeros (not nulls) when meters exist but have never been
 * incremented; null means the meter family isn't registered at all.
 */
public record MetricsSnapshotDto(
        HttpLatency http,
        Routing routing,
        Heap jvmHeap,
        List<TopUri> topUris,
        List<CacheStats> caches) {

    /**
     * Overall {@code http.server.requests} timer aggregated across all URIs.
     * Percentile fields require the histogram be enabled
     * ({@code management.metrics.distribution.percentiles-histogram.http.server.requests=true}).
     */
    public record HttpLatency(
            long count,
            double meanMs,
            double p50Ms,
            double p95Ms,
            double p99Ms,
            double maxMs) { }

    /** Per-endpoint routing dispatch counts. */
    public record Routing(List<EndpointRow> endpoints) {
        public record EndpointRow(
                String endpoint,
                long localSuccess,
                long publicCalls,
                long fallbackTotal,
                Map<String, Long> fallbackByReason) { }
    }

    /** {@code jvm.memory.used{area=heap}} and {@code jvm.memory.max{area=heap}}. */
    public record Heap(long usedBytes, long maxBytes, Double usedPct) { }

    /** One row of the top-5 URIs by request count. */
    public record TopUri(
            String uri,
            String method,
            String status,
            long count,
            double meanMs,
            double maxMs) { }

    /**
     * One cache. {@code hits}/{@code misses}/{@code hitRatio} can be null on
     * a registry that has nothing recorded yet, and {@code hitRatio} is
     * null on a never-accessed cache (both counters zero).
     */
    public record CacheStats(
            String name,
            String kind,
            Long hits,
            Long misses,
            Double hitRatio) { }
}
