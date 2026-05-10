package com.pjr22.tripweather.config;

/**
 * Admin-console credential and toggles, populated from {@code trip.admin.*}
 * properties. Phase 0 of ADMIN_CONSOLE.md.
 *
 * <p>Built once at startup from a {@link org.springframework.context.annotation.Bean Bean}
 * factory in {@link SecurityConfig} so {@link StartupConfigValidator} can rely on the
 * raw environment values being present before any consumer reads them.
 *
 * <p>{@code refreshToken} is the legacy {@code X-Admin-Token} shared secret
 * (not part of the username/password admin login). It pre-dates this feature
 * and stays here so all admin-related config lives in one place.
 */
public record AdminAuthProperties(
        boolean enabled,
        String username,
        String password,
        String refreshToken) {
}
