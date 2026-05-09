package com.pjr22.tripweather.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.pjr22.tripweather.repository.RoutingCoverageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Locale;

/**
 * Picks between local and public ORS per request, falling back to public on
 * any local-engine failure. Sits inside the apiCall lambda passed to
 * {@code RouteService.getOrFetchCached(...)}, so the response cache wraps the
 * dispatch decision rather than the other way around: a cache hit skips both
 * engines.
 *
 * <p>Decision per request:
 * <pre>
 *   if no local client (disabled/missing)              → public, tag=disabled
 *   else if any waypoint outside enabled coverage      → public, tag=out_of_coverage
 *   else try local
 *           on timeout                                 → public, tag=timeout
 *           on other RestClientException / 5xx          → public, tag=upstream_error
 *           on success                                 → return result
 * </pre>
 *
 * <p>The coverage check uses a single PostGIS query that evaluates
 * containment for the whole multipoint route in one round-trip — see
 * {@link RoutingCoverageRepository#coversAll(String)}.
 */
@Component
@Slf4j
public class RoutingDispatcher {

    private final OrsClient publicClient;
    private final ObjectProvider<LocalOrsClient> localClientProvider;
    private final RoutingCoverageRepository coverageRepository;
    private final RoutingMetrics metrics;

    public RoutingDispatcher(PublicOrsClient publicClient,
                             ObjectProvider<LocalOrsClient> localClientProvider,
                             RoutingCoverageRepository coverageRepository,
                             RoutingMetrics metrics) {
        this.publicClient = publicClient;
        this.localClientProvider = localClientProvider;
        this.coverageRepository = coverageRepository;
        this.metrics = metrics;
    }

    /**
     * Dispatches one request. {@code coordinates} is the list of points
     * (each {@code [lon, lat]}) the dispatcher should coverage-check —
     * waypoints for directions, the single point for snap/elevation.
     */
    public JsonNode dispatch(String endpoint,
                             List<double[]> coordinates,
                             OrsCall call) throws Exception {
        LocalOrsClient localClient = localClientProvider.getIfAvailable();
        if (localClient == null) {
            metrics.localFallback(endpoint, RoutingMetrics.Reason.DISABLED);
            return invokePublic(endpoint, call);
        }

        if (!isCovered(coordinates)) {
            metrics.localFallback(endpoint, RoutingMetrics.Reason.OUT_OF_COVERAGE);
            return invokePublic(endpoint, call);
        }

        try {
            JsonNode result = call.invoke(localClient);
            metrics.localSuccess(endpoint);
            return result;
        } catch (ResourceAccessException e) {
            log.warn("Local ORS {} timed out / unreachable; falling back to public: {}",
                    endpoint, e.getMessage());
            metrics.localFallback(endpoint, RoutingMetrics.Reason.TIMEOUT);
            return invokePublic(endpoint, call);
        } catch (Exception e) {
            log.warn("Local ORS {} failed; falling back to public: {}",
                    endpoint, e.getMessage());
            metrics.localFallback(endpoint, RoutingMetrics.Reason.UPSTREAM_ERROR);
            return invokePublic(endpoint, call);
        }
    }

    private JsonNode invokePublic(String endpoint, OrsCall call) throws Exception {
        metrics.publicCall(endpoint);
        return call.invoke(publicClient);
    }

    private boolean isCovered(List<double[]> coordinates) {
        if (coordinates == null || coordinates.isEmpty()) {
            return false;
        }
        try {
            return coverageRepository.coversAll(toMultipointWkt(coordinates));
        } catch (Exception e) {
            // PostGIS / DB hiccup shouldn't break routing — treat as uncovered
            // so we fall back to public, the safe default.
            log.warn("Coverage check failed; treating as uncovered: {}", e.getMessage());
            return false;
        }
    }

    private static String toMultipointWkt(List<double[]> coordinates) {
        StringBuilder sb = new StringBuilder("MULTIPOINT(");
        for (int i = 0; i < coordinates.size(); i++) {
            double[] c = coordinates.get(i);
            if (i > 0) {
                sb.append(',');
            }
            // Locale.ROOT so the decimal separator is always '.', regardless
            // of the JVM's default locale (ST_GeomFromText would reject ',').
            sb.append(String.format(Locale.ROOT, "%.6f %.6f", c[0], c[1]));
        }
        sb.append(')');
        return sb.toString();
    }

    /**
     * Strategy supplied by the caller — runs the same operation against
     * whichever {@link OrsClient} the dispatcher chose.
     */
    @FunctionalInterface
    public interface OrsCall {
        JsonNode invoke(OrsClient client) throws Exception;
    }
}
