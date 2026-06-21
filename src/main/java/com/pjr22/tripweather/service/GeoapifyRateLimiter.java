package com.pjr22.tripweather.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Global pacer for outbound Geoapify requests. Geoapify rate-limits per account
 * (not per endpoint), so every Geoapify call in the app — the forward-geocode
 * search box, map-click reverse-geocodes, and the AI-assist geocoding fan-out —
 * shares one budget. This enforces a minimum spacing between request <em>starts</em>
 * so concurrent callers can't collectively exceed the limit.
 *
 * <p>Implementation is a leaky-bucket: each {@link #acquire()} reserves the next
 * slot ({@code max(now, nextSlot)}) and advances the cursor by the configured
 * interval, then sleeps until its slot. The slot arithmetic is synchronized; the
 * sleep is not, so N waiting threads get N distinct slots spaced
 * {@code min-request-interval-ms} apart and sleep concurrently until theirs.
 *
 * <p>The interval is configured as a <b>period</b>
 * ({@code trip.geocode.min-request-interval-ms}, default 220 ms ≈ 4.5 req/s) so
 * it maps directly to "one request every N ms". Set to 0 to disable pacing
 * (e.g. in tests).
 */
@Component
public class GeoapifyRateLimiter {

    private final long minIntervalMillis;

    /** Earliest wall-clock millis at which the next request may start. */
    private long nextAllowedAtMillis = 0L;

    public GeoapifyRateLimiter(
            @Value("${trip.geocode.min-request-interval-ms:220}") long minIntervalMillis) {
        this.minIntervalMillis = Math.max(0, minIntervalMillis);
    }

    /**
     * Block until the caller is allowed to start its Geoapify request. Returns
     * immediately when pacing is disabled or the next slot is already due.
     * Restores the interrupt flag and returns early if interrupted while waiting.
     */
    public void acquire() {
        if (minIntervalMillis == 0) {
            return;
        }
        long waitMillis;
        synchronized (this) {
            long now = System.currentTimeMillis();
            long slot = Math.max(now, nextAllowedAtMillis);
            nextAllowedAtMillis = slot + minIntervalMillis;
            waitMillis = slot - now;
        }
        if (waitMillis > 0) {
            try {
                Thread.sleep(waitMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
