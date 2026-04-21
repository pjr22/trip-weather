package com.pjr22.tripweather.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Applies security response headers to every response:
 *   - Content-Security-Policy (or Content-Security-Policy-Report-Only when not enforcing)
 *   - X-Content-Type-Options: nosniff
 *   - Referrer-Policy: strict-origin-when-cross-origin
 *
 * The CSP restricts resource origins to only what the app actually loads (Leaflet from
 * unpkg.com; map tiles from OpenStreetMap; weather WMS tiles from digital.weather.gov;
 * everything else same-origin). 'unsafe-inline' remains enabled for style-src because
 * Leaflet uses inline styles internally and a handful of inline style attributes remain
 * in our markup; script-src stays strict (no 'unsafe-inline') now that all inline onclick
 * handlers have been removed.
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final String CSP_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self' https://unpkg.com",
            "style-src 'self' https://unpkg.com 'unsafe-inline'",
            // unpkg.com also hosts Leaflet's default marker icon PNGs (loaded as <img>).
            // api.weather.gov hosts the weather-condition icon PNGs embedded in NWS forecast responses.
            "img-src 'self' data: https://unpkg.com https://*.tile.openstreetmap.org https://digital.weather.gov https://api.weather.gov",
            // unpkg.com permitted so DevTools can fetch the Leaflet sourcemap; production users never hit this.
            "connect-src 'self' https://unpkg.com",
            "font-src 'self'",
            "frame-ancestors 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "object-src 'none'"
    );

    @Value("${trip.csp.enforce:true}")
    private boolean enforceCsp;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String cspHeaderName = enforceCsp
                ? "Content-Security-Policy"
                : "Content-Security-Policy-Report-Only";
        response.setHeader(cspHeaderName, CSP_POLICY);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        chain.doFilter(request, response);
    }
}
