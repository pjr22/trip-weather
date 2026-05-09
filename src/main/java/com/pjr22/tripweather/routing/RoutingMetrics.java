package com.pjr22.tripweather.routing;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Three counters exposed at {@code /actuator/metrics/trip.routing.*}:
 *
 * <ul>
 *   <li>{@code trip.routing.local.success{endpoint}} — local ORS answered
 *       a request that the dispatch wrapper had decided to route locally.</li>
 *   <li>{@code trip.routing.local.fallback{endpoint, reason}} — wanted local
 *       (or would have, if the engine were on) but fell through. {@code reason}
 *       is one of {@code disabled}, {@code out_of_coverage}, {@code timeout},
 *       {@code upstream_error}.</li>
 *   <li>{@code trip.routing.public.calls{endpoint}} — public ORS calls,
 *       whether by direct dispatch (no local) or as a fallback.</li>
 * </ul>
 *
 * <p>The fallback rate (sum of {@code fallback}{@code reason!=disabled}
 * divided by attempted-local) signals when coverage expansion would be
 * worthwhile — high {@code reason="out_of_coverage"} means routes regularly
 * fall outside what the local engine knows about.
 *
 * <p>All known endpoint × reason combinations are eagerly registered at
 * startup so they appear in {@code /actuator/metrics} and
 * {@code /actuator/prometheus} as {@code count=0} from boot — useful for
 * dashboards and "is the metric wired up?" sanity checks before traffic
 * flows. Without this, Micrometer registers each counter on first
 * increment, so a counter that hasn't been touched yet looks identical to
 * a counter that doesn't exist.
 */
@Component
public class RoutingMetrics {

    private static final String ENDPOINT_TAG = "endpoint";
    private static final String REASON_TAG   = "reason";

    private static final String[] ENDPOINTS = {"directions", "snap", "elevation"};
    private static final String[] REASONS = {
            Reason.DISABLED,
            Reason.OUT_OF_COVERAGE,
            Reason.TIMEOUT,
            Reason.UPSTREAM_ERROR
    };

    private final MeterRegistry registry;

    public RoutingMetrics(MeterRegistry registry) {
        this.registry = registry;
        eagerlyRegisterAll();
    }

    private void eagerlyRegisterAll() {
        for (String endpoint : ENDPOINTS) {
            Counter.builder("trip.routing.local.success")
                    .tag(ENDPOINT_TAG, endpoint).register(registry);
            Counter.builder("trip.routing.public.calls")
                    .tag(ENDPOINT_TAG, endpoint).register(registry);
            for (String reason : REASONS) {
                Counter.builder("trip.routing.local.fallback")
                        .tag(ENDPOINT_TAG, endpoint)
                        .tag(REASON_TAG, reason)
                        .register(registry);
            }
        }
    }

    public void localSuccess(String endpoint) {
        Counter.builder("trip.routing.local.success")
                .tag(ENDPOINT_TAG, endpoint)
                .register(registry)
                .increment();
    }

    public void localFallback(String endpoint, String reason) {
        Counter.builder("trip.routing.local.fallback")
                .tag(ENDPOINT_TAG, endpoint)
                .tag(REASON_TAG, reason)
                .register(registry)
                .increment();
    }

    public void publicCall(String endpoint) {
        Counter.builder("trip.routing.public.calls")
                .tag(ENDPOINT_TAG, endpoint)
                .register(registry)
                .increment();
    }

    /** Reason values used as tag values on the fallback counter. */
    public static final class Reason {
        public static final String DISABLED         = "disabled";
        public static final String OUT_OF_COVERAGE  = "out_of_coverage";
        public static final String TIMEOUT          = "timeout";
        public static final String UPSTREAM_ERROR   = "upstream_error";

        private Reason() { }
    }
}
