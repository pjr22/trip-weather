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
 * Most directives are static. {@code img-src} is dynamic because Phase 4's
 * tile proxy can be off (allow upstream tile/WMS/icon hosts), on with relative
 * paths (just {@code 'self'}), or on with an absolute base URL (whitelist that
 * origin). Constructed once at startup from {@link TileProxyConfig}.
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private final String cspPolicy;
    private final boolean enforceCsp;

    public SecurityHeadersFilter(TileProxyConfig tileProxyConfig,
                                 @Value("${trip.csp.enforce:true}") boolean enforceCsp) {
        this.enforceCsp = enforceCsp;
        this.cspPolicy = String.join("; ",
                "default-src 'self'",
                "script-src 'self' https://unpkg.com",
                "style-src 'self' https://unpkg.com 'unsafe-inline'",
                "img-src " + tileProxyConfig.cspImgSrc(),
                // unpkg.com permitted so DevTools can fetch the Leaflet sourcemap; production users never hit this.
                "connect-src 'self' https://unpkg.com",
                "font-src 'self'",
                "frame-ancestors 'none'",
                "base-uri 'self'",
                "form-action 'self'",
                "object-src 'none'"
        );
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String cspHeaderName = enforceCsp
                ? "Content-Security-Policy"
                : "Content-Security-Policy-Report-Only";
        response.setHeader(cspHeaderName, cspPolicy);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        chain.doFilter(request, response);
    }
}
