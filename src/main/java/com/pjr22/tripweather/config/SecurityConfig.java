package com.pjr22.tripweather.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Spring Security configuration for the SPA + JSON-API model.
 *
 * Phase 1 keeps every endpoint that is currently public (anonymous saves,
 * loads, searches, and the third-party API proxies) public. Authenticated
 * paths land in later phases (DELETE on routes, write endpoints under
 * /api/auth/**). CSRF is enabled now so the cookie/header handshake is in
 * place before any state-changing auth endpoint exists.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ObjectProvider<AbstractRememberMeServices> rememberMeServicesProvider) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        SpaCsrfTokenRequestHandler csrfHandler = new SpaCsrfTokenRequestHandler();

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfRepository)
                .csrfTokenRequestHandler(csrfHandler)
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

    @Bean
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
