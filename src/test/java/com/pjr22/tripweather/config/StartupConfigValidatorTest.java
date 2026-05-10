package com.pjr22.tripweather.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StartupConfigValidatorTest {

    private static MockEnvironment baseValidEnv() {
        // Minimum the validator requires for a clean run with all features
        // enabled. Each test below tweaks one knob.
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.password", "db-password");
        env.setProperty("trip.email.enabled", "false");
        env.setProperty("trip.auth.remember-me.enabled", "false");
        env.setProperty("trip.admin.enabled", "false");
        return env;
    }

    @Test
    void admin_enabled_requires_username_and_password() {
        MockEnvironment env = baseValidEnv();
        env.setProperty("trip.admin.enabled", "true");
        // username + password missing — must abort
        assertThatThrownBy(() -> new StartupConfigValidator().postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TRIP_ADMIN_USERNAME")
                .hasMessageContaining("TRIP_ADMIN_PASSWORD");
    }

    @Test
    void admin_enabled_with_only_username_still_fails() {
        MockEnvironment env = baseValidEnv();
        env.setProperty("trip.admin.enabled", "true");
        env.setProperty("trip.admin.username", "alice");
        // password missing
        assertThatThrownBy(() -> new StartupConfigValidator().postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void admin_enabled_with_only_password_still_fails() {
        MockEnvironment env = baseValidEnv();
        env.setProperty("trip.admin.enabled", "true");
        env.setProperty("trip.admin.password", "s3cret");
        // username missing
        assertThatThrownBy(() -> new StartupConfigValidator().postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void admin_enabled_with_both_set_passes() {
        MockEnvironment env = baseValidEnv();
        env.setProperty("trip.admin.enabled", "true");
        env.setProperty("trip.admin.username", "alice");
        env.setProperty("trip.admin.password", "s3cret");

        assertThatCode(() -> new StartupConfigValidator().postProcessEnvironment(env, null))
                .doesNotThrowAnyException();
    }

    @Test
    void admin_disabled_does_not_require_username_or_password() {
        // Default for fresh checkouts that don't want to provision admin —
        // matches the documented TRIP_ADMIN_ENABLED=false escape hatch.
        MockEnvironment env = baseValidEnv();
        env.setProperty("trip.admin.enabled", "false");

        assertThatCode(() -> new StartupConfigValidator().postProcessEnvironment(env, null))
                .doesNotThrowAnyException();
    }

    @Test
    void db_password_requirement_still_enforced() {
        // Regression cover for the original responsibility of this validator.
        MockEnvironment env = baseValidEnv();
        env.setProperty("spring.datasource.password", "");
        assertThatThrownBy(() -> new StartupConfigValidator().postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TRIP_DB_PASSWORD");
    }
}
