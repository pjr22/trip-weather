package com.pjr22.tripweather.config;

import com.pjr22.tripweather.security.AdminUserDetailsService;
import com.pjr22.tripweather.security.XAdminTokenAuthenticationFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Spring Security configuration. Two ordered filter chains:
 *
 * <ol>
 *   <li>{@link #adminSecurityChain} — owns {@code /admin/**} and {@code /api/admin/**}.
 *       Uses a separate session-context attribute key so an admin login cannot bleed
 *       a {@code ROLE_ADMIN} principal into user-chain endpoints. The
 *       {@link XAdminTokenAuthenticationFilter} grants {@code ROLE_ADMIN_TOKEN} on a
 *       matching {@code X-Admin-Token} header — recognised only by the legacy
 *       {@code refresh-coverage} endpoint, so the production cron keeps working
 *       without granting console-wide access.</li>
 *   <li>{@link #userSecurityChain} — everything else. SPA + JSON-API model with CSRF
 *       on a cookie token, anonymous reads of {@code /api/routes} and the third-party
 *       proxies, authenticated writes under {@code /api/auth/**} and {@code DELETE
 *       /api/routes/**}.</li>
 * </ol>
 *
 * <p>The two chains share the same {@code JSESSIONID} but namespace their security
 * context inside the session (default key for the user chain, {@code
 * SPRING_SECURITY_CONTEXT_ADMIN} for the admin chain). This keeps a single browser
 * able to be both a regular user (in the main app) and the admin (in the console)
 * without one role leaking into the other chain's authorization checks.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * HttpSession attribute under which the admin chain stores its
     * {@link org.springframework.security.core.context.SecurityContext}. Must be
     * different from the default ({@code SPRING_SECURITY_CONTEXT}) so admin and
     * user authentications don't overwrite each other in the same session.
     */
    public static final String ADMIN_SECURITY_CONTEXT_KEY = "SPRING_SECURITY_CONTEXT_ADMIN";

    @Bean
    public AdminAuthProperties adminAuthProperties(
            @Value("${trip.admin.enabled:true}") boolean enabled,
            @Value("${trip.admin.username:}") String username,
            @Value("${trip.admin.password:}") String password,
            @Value("${trip.admin.refresh-token:}") String refreshToken) {
        return new AdminAuthProperties(
                enabled,
                username == null ? "" : username.trim(),
                password == null ? "" : password,
                refreshToken == null ? "" : refreshToken.trim());
    }

    /**
     * {@link SecurityContextRepository} for the admin chain — same session as the
     * user chain, but stored under {@link #ADMIN_SECURITY_CONTEXT_KEY} so the
     * admin principal isn't visible to user-chain authorization checks (and vice
     * versa). Deliberately not a top-level bean: declaring a
     * {@code SecurityContextRepository} bean would cause Spring Security's
     * {@code HttpSecurityConfiguration} to pick it up as a shared object for
     * <em>both</em> chains, leaking the admin attribute key into user-chain
     * persistence. Each consumer instantiates its own using
     * {@link #ADMIN_SECURITY_CONTEXT_KEY} as the shared coupling point.
     */
    public static SecurityContextRepository newAdminSecurityContextRepository() {
        HttpSessionSecurityContextRepository repository = new HttpSessionSecurityContextRepository();
        repository.setSpringSecurityContextKey(ADMIN_SECURITY_CONTEXT_KEY);
        return repository;
    }

    /**
     * {@link AuthenticationManager} backing {@code POST /api/admin/login}. Built
     * from {@link AdminUserDetailsService}, which BCrypts the configured admin
     * password once at startup. Distinct from the {@link #authenticationManager
     * primary AuthenticationManager} the user chain uses (which talks to the
     * users table via {@code TripUserDetailsService}).
     */
    @Bean("adminAuthenticationManager")
    public AuthenticationManager adminAuthenticationManager(
            AdminAuthProperties props, PasswordEncoder encoder) {
        if (!props.enabled()) {
            // Bean is still created so injection sites can keep their
            // dependencies, but the manager rejects everything. Paired with
            // the chain's authorizeHttpRequests denyAll for /api/admin/login
            // when admin is disabled.
            return authentication -> {
                throw new org.springframework.security.authentication.DisabledException(
                        "Admin login is disabled (trip.admin.enabled=false)");
            };
        }
        DaoAuthenticationProvider provider =
                new DaoAuthenticationProvider(new AdminUserDetailsService(props, encoder));
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    @Order(1)
    public SecurityFilterChain adminSecurityChain(HttpSecurity http,
                                                  AdminAuthProperties props) throws Exception {
        SecurityContextRepository adminSecurityContextRepository = newAdminSecurityContextRepository();
        http
            .securityMatcher("/admin/**", "/api/admin/**")
            // CSRF off on the admin chain — admin endpoints are JSON POST from
            // the admin SPA (which sends an HttpOnly+SameSite=Strict session
            // cookie) and from the production cron (token-authenticated, no
            // browser involved). Matches the user chain's pattern of ignoring
            // /api/admin/** for CSRF.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .securityContext(s -> s
                .securityContextRepository(adminSecurityContextRepository)
                .requireExplicitSave(true))
            .addFilterBefore(
                new XAdminTokenAuthenticationFilter(props.refreshToken()),
                AuthorizationFilter.class)
            .exceptionHandling(eh -> {
                // Browser hits to /admin/** that aren't authenticated land on
                // the login page; XHR hits to /api/admin/** get a clean 401 so
                // the SPA's fetch wrapper can redirect.
                PathPatternRequestMatcher.Builder matchers = PathPatternRequestMatcher.withDefaults();
                eh.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/admin/login.html"),
                        matchers.matcher("/admin/**"));
                eh.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        matchers.matcher("/api/admin/**"));
            })
            .authorizeHttpRequests(auth -> auth
                // Public assets needed to render the login page
                .requestMatchers(HttpMethod.GET,
                        "/admin/login.html",
                        "/admin/admin.css",
                        "/admin/js/login.js").permitAll()
                // Login endpoint must be reachable to anonymous callers
                .requestMatchers(HttpMethod.POST, "/api/admin/login").permitAll()
                // Logout works whether or not the session is currently valid
                .requestMatchers(HttpMethod.POST, "/api/admin/logout").permitAll()
                // Legacy refresh-coverage endpoint accepts either an admin
                // session OR the X-Admin-Token header (granted ROLE_ADMIN_TOKEN
                // by XAdminTokenAuthenticationFilter)
                .requestMatchers(HttpMethod.POST, "/api/admin/refresh-coverage/**")
                        .hasAnyRole("ADMIN", "ADMIN_TOKEN")
                // Everything else under /admin/** and /api/admin/** requires
                // a real admin session (no token-only access)
                .anyRequest().hasRole("ADMIN"))
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable());

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain userSecurityChain(HttpSecurity http,
                                                 ObjectProvider<AbstractRememberMeServices> rememberMeServicesProvider) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        SpaCsrfTokenRequestHandler csrfHandler = new SpaCsrfTokenRequestHandler();

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfRepository)
                .csrfTokenRequestHandler(csrfHandler)
                // Admin endpoints have their own chain; ignoring here is a
                // belt-and-braces no-op (this chain doesn't match /api/admin/**
                // anyway, but a future refactor might widen the matcher).
                // Actuator endpoints are denied at the authorize layer above;
                // the ignore is so a misrouted POST returns 403, not 403+CSRF.
                .ignoringRequestMatchers("/api/admin/**", "/actuator/**")
            )
            // Force the CSRF cookie to be written on every request so the SPA can
            // pick it up before its first state-changing call. Without this, the
            // deferred CsrfToken supplier never runs on a pure GET and the cookie
            // doesn't appear until the first POST — too late for the save flow.
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .authorizeHttpRequests(auth -> auth
                // /me is read-only and used by the SPA on every page load
                .requestMatchers(HttpMethod.GET, "/api/auth/me").permitAll()
                // Self-service auth endpoints (anonymous callers expected)
                .requestMatchers(HttpMethod.POST,
                        "/api/auth/signup",
                        "/api/auth/verify",
                        "/api/auth/resend-verification",
                        "/api/auth/login",
                        "/api/auth/forgot-password",
                        "/api/auth/reset-password").permitAll()
                // Logout requires an active session
                .requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
                // Catch-all under /api/auth: any future endpoint is authenticated
                // unless explicitly opened above (change-password and
                // delete-account fall under this catch-all)
                .requestMatchers("/api/auth/**").authenticated()
                // Actuator endpoints (Phase 5 metrics + health + prometheus)
                // are server-internal only by haproxy convention — the
                // public frontend doesn't route /actuator/** outward.
                // Spring Security permits them so the operator can read
                // them from localhost in dev and from the private network
                // in prod. Only the safe endpoints are exposed (see
                // management.endpoints.web.exposure.include); env, beans,
                // configprops etc. remain off the wire entirely.
                .requestMatchers("/actuator/**").permitAll()
                // Existing anonymous save/load/search flows stay public
                .requestMatchers(HttpMethod.POST, "/api/routes").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/routes/**").permitAll()
                // Future state-changing route endpoint (Phase 3)
                .requestMatchers(HttpMethod.DELETE, "/api/routes/**").authenticated()
                // Everything else (static SPA, third-party proxies) stays open
                .anyRequest().permitAll()
            )
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable());

        // Wire the remember-me filter so a stored cookie re-authenticates on
        // the next request after a browser restart. The cookie is *only*
        // issued when LoginRequest.rememberMe=true (AuthController calls
        // rememberMeServices.loginSuccess explicitly); here we just register
        // the services so the filter runs. Skipped entirely when
        // trip.auth.remember-me.enabled=false (the bean is absent and we
        // don't add the filter).
        AbstractRememberMeServices rememberMeServices = rememberMeServicesProvider.getIfAvailable();
        if (rememberMeServices != null) {
            http.rememberMe(rm -> rm.rememberMeServices(rememberMeServices));
        }

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * The user-chain {@link AuthenticationManager}. Marked {@code @Primary} so
     * existing {@code AuthenticationManager} injection points (e.g.
     * {@link com.pjr22.tripweather.controller.AuthController}) keep resolving
     * to it after the admin one is registered as a second bean.
     */
    @Bean
    @Primary
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * Forces the deferred CSRF token to be loaded on every request, which causes
     * {@link CookieCsrfTokenRepository} to write the XSRF-TOKEN cookie. Required
     * for SPA flows where the first call after page load is a state-changing POST.
     */
    private static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
