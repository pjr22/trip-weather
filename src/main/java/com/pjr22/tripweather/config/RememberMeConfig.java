package com.pjr22.tripweather.config;

import com.pjr22.tripweather.service.UserAccountService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;

import javax.sql.DataSource;

/**
 * Wires Spring Security's persistent-token "remember me" implementation.
 *
 * Token storage lives in the {@code persistent_logins} table (added by
 * user-accounts-db-migration.sh). Choosing the persistent-token flavour over
 * the simpler hashed-cookie flavour buys server-side revocation: change-password,
 * reset-password, and delete-account all wipe the user's rows from this table,
 * which immediately invalidates any other browser holding a remember-me cookie.
 *
 * Cookie name {@code tripweather-remember-me} (Spring's default {@code remember-me}
 * is too generic). HttpOnly + Secure + SameSite=Lax come from Spring Security
 * defaults plus the existing {@code TRIP_COOKIE_SECURE} configuration.
 *
 * The whole feature can be disabled in dev with {@code TRIP_REMEMBER_ME_ENABLED=false}
 * — the persistent-token beans then aren't registered, the SecurityConfig skips
 * the rememberMe wiring, and a no-op revoker keeps UserAccountService happy.
 */
@Configuration
public class RememberMeConfig {

    /** Same name referenced in SecurityConfig.rememberMe(...). */
    public static final String COOKIE_NAME = "tripweather-remember-me";

    /** Default validity in seconds — 30 days. Matches the user-facing copy. */
    public static final int DEFAULT_VALIDITY_SECONDS = 30 * 24 * 60 * 60;

    /**
     * Active-by-default beans for the persistent-token flavour. Disabled
     * collectively when {@code trip.auth.remember-me.enabled=false}.
     */
    @Configuration
    @ConditionalOnProperty(name = "trip.auth.remember-me.enabled",
                           havingValue = "true", matchIfMissing = true)
    static class Enabled {

        @Bean
        public PersistentTokenRepository persistentTokenRepository(DataSource dataSource) {
            JdbcTokenRepositoryImpl repo = new JdbcTokenRepositoryImpl();
            repo.setDataSource(dataSource);
            // The migration script creates the table directly so it can also
            // add the username index; leave createTableOnStartup=false (default).
            return repo;
        }

        @Bean
        public PersistentTokenBasedRememberMeServices persistentTokenBasedRememberMeServices(
                @Value("${trip.auth.remember-me.key}") String key,
                UserDetailsService userDetailsService,
                PersistentTokenRepository tokenRepository) {
            PersistentTokenBasedRememberMeServices services = new PersistentTokenBasedRememberMeServices(
                    key, userDetailsService, tokenRepository);
            services.setCookieName(COOKIE_NAME);
            services.setTokenValiditySeconds(DEFAULT_VALIDITY_SECONDS);
            return services;
        }

        @Bean
        public UserAccountService.RememberMeRevoker rememberMeRevoker(
                PersistentTokenRepository tokenRepository) {
            return tokenRepository::removeUserTokens;
        }
    }

    /**
     * Fallback when the feature is disabled — UserAccountService still asks
     * for a revoker on every change-password / reset / delete-account, so we
     * give it one that does nothing rather than null-checking everywhere.
     */
    @Bean
    @ConditionalOnMissingBean(UserAccountService.RememberMeRevoker.class)
    public UserAccountService.RememberMeRevoker noopRememberMeRevoker() {
        return username -> { /* remember-me disabled — nothing to revoke */ };
    }
}
