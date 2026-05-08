package com.pjr22.tripweather.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices.RememberMeTokenAlgorithm;

/**
 * Wires Spring Security's stateless "remember me" implementation.
 *
 * Uses {@link TokenBasedRememberMeServices} (the hashed-cookie flavour). The
 * cookie is {@code base64(username:expiryTime:SHA256(username:expiryTime:passwordHash:key))}
 * — no server-side state. Two consequences worth knowing:
 *
 * <ul>
 *   <li>Revocation on password change / reset / account-delete is automatic:
 *       the password hash is part of the signature, so any change to it
 *       invalidates every outstanding cookie for that user.</li>
 *   <li>There is no {@code persistent_logins} table. The earlier persistent-token
 *       implementation was removed because its rotation-on-every-auto-login
 *       design races with concurrent SPA requests after a backend restart, and
 *       the only feature it added on top of token-based — theft-via-rotation
 *       detection — was never operationalised in this app.</li>
 * </ul>
 *
 * Cookie name is {@code tripweather-remember-me} (Spring's default
 * {@code remember-me} is too generic). HttpOnly + SameSite=Lax + Secure (when
 * the request is HTTPS) come from Spring Security defaults.
 *
 * The whole feature can be disabled in dev with {@code TRIP_REMEMBER_ME_ENABLED=false}
 * — the bean is then absent and the SecurityConfig skips the rememberMe wiring.
 */
@Configuration
public class RememberMeConfig {

    /** Same name referenced in SecurityConfig.rememberMe(...). */
    public static final String COOKIE_NAME = "tripweather-remember-me";

    /** Default validity in seconds — 30 days. Matches the user-facing copy. */
    public static final int DEFAULT_VALIDITY_SECONDS = 30 * 24 * 60 * 60;

    @Bean
    @ConditionalOnProperty(name = "trip.auth.remember-me.enabled",
                           havingValue = "true", matchIfMissing = true)
    public TokenBasedRememberMeServices rememberMeServices(
            @Value("${trip.auth.remember-me.key}") String key,
            UserDetailsService userDetailsService) {
        TokenBasedRememberMeServices services = new TokenBasedRememberMeServices(
                key, userDetailsService, RememberMeTokenAlgorithm.SHA256);
        services.setCookieName(COOKIE_NAME);
        services.setTokenValiditySeconds(DEFAULT_VALIDITY_SECONDS);
        // AbstractRememberMeServices.loginSuccess() will silently no-op unless
        // it can find a "remember-me" form parameter on the request. Our login
        // is a JSON POST, so that parameter never exists. Setting alwaysRemember
        // tells the bean to skip the form-parameter check and issue the cookie
        // whenever loginSuccess is invoked. The decision of whether to invoke
        // it stays at the controller, which gates on LoginRequest.rememberMe.
        services.setAlwaysRemember(true);
        return services;
    }
}
