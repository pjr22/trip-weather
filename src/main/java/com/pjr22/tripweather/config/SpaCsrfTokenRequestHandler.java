package com.pjr22.tripweather.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

/**
 * SPA-friendly CSRF token handler. Spring Security's default uses a BREACH-resistant
 * XOR-encoded token in the request attribute, which means the cookie value (which the
 * SPA reads directly) won't match. This handler:
 *   - writes the XOR-encoded value into the request (Spring's default behaviour), so
 *     server-rendered forms still work,
 *   - on validation, prefers the raw cookie value when the SPA submits the
 *     X-XSRF-TOKEN header — that's what {@code CookieCsrfTokenRepository} writes.
 *
 * Pattern from the Spring Security reference: "CSRF — Configuring CSRF in a SPA".
 */
public class SpaCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

    private final CsrfTokenRequestHandler delegate = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       Supplier<CsrfToken> csrfToken) {
        this.delegate.handle(request, response, csrfToken);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        if (StringUtils.hasText(request.getHeader(csrfToken.getHeaderName()))) {
            return super.resolveCsrfTokenValue(request, csrfToken);
        }
        return this.delegate.resolveCsrfTokenValue(request, csrfToken);
    }
}
