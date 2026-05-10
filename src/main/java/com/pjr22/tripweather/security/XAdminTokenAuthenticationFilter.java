package com.pjr22.tripweather.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Pre-authentication filter that recognises the legacy {@code X-Admin-Token}
 * header and grants a {@code ROLE_ADMIN_TOKEN} authority. Only the
 * {@code POST /api/admin/refresh-coverage/{region}} endpoint accepts this
 * authority — every other admin endpoint requires {@code ROLE_ADMIN} from a
 * proper login. This is how the production cron in {@code docker/refreshOrsGraph.sh}
 * keeps working without granting the cron full admin-console access.
 *
 * <p>Wired into the admin {@link org.springframework.security.web.SecurityFilterChain}
 * before the {@link org.springframework.security.web.access.intercept.AuthorizationFilter}
 * so the authority is in the {@link SecurityContextHolder} when authorization
 * runs. Stateless — it sets a per-request authentication and never persists it
 * to a session.
 *
 * <p>If {@code trip.admin.refresh-token} is blank (the default), the filter is
 * a no-op: a missing or any header is ignored, leaving the request anonymous
 * so the authorization layer rejects it. Fail-closed.
 */
public class XAdminTokenAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Admin-Token";
    public static final String ROLE_ADMIN_TOKEN = "ROLE_ADMIN_TOKEN";
    /** Principal name surfaced for log lines / audit; not a real user. */
    public static final String PRINCIPAL_NAME = "admin-token-caller";

    private final String expectedToken;

    public XAdminTokenAuthenticationFilter(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (expectedToken != null && !expectedToken.isBlank()) {
            String header = request.getHeader(HEADER_NAME);
            if (header != null && expectedToken.equals(header)) {
                Authentication auth = new AdminTokenAuthentication(PRINCIPAL_NAME);
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(auth);
                SecurityContextHolder.setContext(context);
            }
        }
        // Spring 6's SecurityContextHolderFilter clears the holder on chain
        // exit, and the chain is configured with requireExplicitSave so the
        // session repository never picks up this transient authority. So
        // there's nothing extra to do on the way out — the auth lives only
        // for the duration of this request.
        chain.doFilter(request, response);
    }

    /**
     * Marker authentication so log filters / audit logs can distinguish a
     * token-bearing call from a session-authenticated admin without relying
     * on authority strings alone.
     */
    public static final class AdminTokenAuthentication extends AbstractAuthenticationToken {
        private final String principal;

        public AdminTokenAuthentication(String principal) {
            super(List.of(new SimpleGrantedAuthority(ROLE_ADMIN_TOKEN)));
            this.principal = principal;
            setAuthenticated(true);
        }

        @Override public Object getCredentials() { return ""; }
        @Override public Object getPrincipal() { return principal; }
    }
}
