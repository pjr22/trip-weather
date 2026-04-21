package com.pjr22.tripweather.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-IP rate limit for endpoints that proxy to paid / quota-bound third-party APIs
 * (NREL, OpenRouteService, GeoApify, weather.gov, digital.weather.gov WMS).
 *
 * Keeps one token bucket per client IP in memory. Deployed behind haproxy, so the
 * client IP is taken from the first value of the X-Forwarded-For header.
 * Localhost traffic is exempt so local dev isn't throttled.
 */
@Component
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private static final List<String> PROTECTED_PATH_PREFIXES = List.of(
            "/api/location/",
            "/api/ev-charging/",
            "/api/route/",
            "/api/weather/",
            "/api/wms/"
    );

    private static final List<String> EXEMPT_PATH_SUFFIXES = List.of(
            "/health"
    );

    private final ConcurrentHashMap<String, Bucket> bucketsByIp = new ConcurrentHashMap<>();

    @Value("${trip.ratelimit.enabled:true}")
    private boolean enabled;

    @Value("${trip.ratelimit.requests-per-minute:120}")
    private int requestsPerMinute;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!enabled || !isProtectedPath(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = extractClientIp(request);
        if (isLocalhost(clientIp)) {
            chain.doFilter(request, response);
            return;
        }

        Bucket bucket = bucketsByIp.computeIfAbsent(clientIp, ip -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(request, response);
            return;
        }

        long retryAfterSeconds = Math.max(1L, probe.getNanosToWaitForRefill() / 1_000_000_000L);
        log.warn("Rate limit exceeded for IP {} on {}; retry in {}s",
                clientIp, request.getRequestURI(), retryAfterSeconds);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\":\"Rate limit exceeded\",\"retryAfterSeconds\":" + retryAfterSeconds + "}");
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(requestsPerMinute)
                .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    private boolean isProtectedPath(String path) {
        if (path == null) return false;
        boolean matchesPrefix = PROTECTED_PATH_PREFIXES.stream().anyMatch(path::startsWith);
        if (!matchesPrefix) return false;
        return EXEMPT_PATH_SUFFIXES.stream().noneMatch(path::endsWith);
    }

    private String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (!first.isEmpty()) return first;
        }
        return request.getRemoteAddr();
    }

    private boolean isLocalhost(String ip) {
        return "127.0.0.1".equals(ip)
                || "::1".equals(ip)
                || "0:0:0:0:0:0:0:1".equals(ip);
    }
}
