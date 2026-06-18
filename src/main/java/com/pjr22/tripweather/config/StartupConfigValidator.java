package com.pjr22.tripweather.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Fails application startup early with a clear message if required environment
 * variables are missing. Runs before the DataSource is created, so the user gets
 * an actionable error instead of a cryptic PSQLException during bean init.
 *
 * Registered in META-INF/spring.factories (EnvironmentPostProcessor still uses the
 * classic spring.factories mechanism in Spring Boot 3.x — only auto-configurations
 * moved to the newer .imports format).
 */
public class StartupConfigValidator implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String password = environment.getProperty("spring.datasource.password");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "TRIP_DB_PASSWORD environment variable is required but not set.\n"
                  + "Set it before running the application, for example:\n"
                  + "  export TRIP_DB_PASSWORD='<your-db-password>'\n"
                  + "See CODE_REVIEW.md issue #1 for full setup instructions."
            );
        }

        boolean emailEnabled = Boolean.parseBoolean(
                environment.getProperty("trip.email.enabled", "true"));
        if (emailEnabled) {
            String emailUrl = environment.getProperty("trip.email.url");
            String emailKey = environment.getProperty("trip.email.api-key");
            if (emailUrl == null || emailUrl.isBlank()
                    || emailKey == null || emailKey.isBlank()) {
                throw new IllegalStateException(
                        "Email is enabled (trip.email.enabled=true) but TRIP_EMAIL_URL "
                      + "and/or TRIP_EMAIL_APIKEY is missing.\n"
                      + "Set them in the environment, for example:\n"
                      + "  export TRIP_EMAIL_URL='https://send.api.mailtrap.io/api/send'\n"
                      + "  export TRIP_EMAIL_APIKEY='<your-mailtrap-api-token>'\n"
                      + "or disable email locally with TRIP_EMAIL_ENABLED=false."
                );
            }
        }

        boolean rememberMeEnabled = Boolean.parseBoolean(
                environment.getProperty("trip.auth.remember-me.enabled", "true"));
        if (rememberMeEnabled) {
            String rememberMeKey = environment.getProperty("trip.auth.remember-me.key");
            if (rememberMeKey == null || rememberMeKey.isBlank()) {
                throw new IllegalStateException(
                        "Remember-me is enabled (trip.auth.remember-me.enabled=true) but "
                      + "TRIP_REMEMBER_ME_KEY is missing.\n"
                      + "This server-side secret signs the persistent-login cookie. "
                      + "Generate a random string and set it in the environment, for example:\n"
                      + "  export TRIP_REMEMBER_ME_KEY=\"$(openssl rand -base64 48)\"\n"
                      + "or disable the feature locally with TRIP_REMEMBER_ME_ENABLED=false."
                );
            }
        }

        boolean adminEnabled = Boolean.parseBoolean(
                environment.getProperty("trip.admin.enabled", "true"));
        if (adminEnabled) {
            String adminUsername = environment.getProperty("trip.admin.username");
            String adminPassword = environment.getProperty("trip.admin.password");
            if (adminUsername == null || adminUsername.isBlank()
                    || adminPassword == null || adminPassword.isBlank()) {
                throw new IllegalStateException(
                        "Admin console is enabled (trip.admin.enabled=true) but "
                      + "TRIP_ADMIN_USERNAME and/or TRIP_ADMIN_PASSWORD is missing.\n"
                      + "These set the single operator credential for the /admin/ console "
                      + "(BCrypt-hashed in memory at startup). Set them in the environment, "
                      + "for example:\n"
                      + "  export TRIP_ADMIN_USERNAME='admin'\n"
                      + "  export TRIP_ADMIN_PASSWORD=\"$(openssl rand -base64 24)\"\n"
                      + "or disable the console locally with TRIP_ADMIN_ENABLED=false."
                );
            }
        }

        boolean aiAssistEnabled = Boolean.parseBoolean(
                environment.getProperty("trip.ai.assist.enabled", "true"));
        if (aiAssistEnabled) {
            String aiEncKey = environment.getProperty("trip.ai.enc-key");
            if (aiEncKey == null || aiEncKey.isBlank()) {
                throw new IllegalStateException(
                        "AI assistant is enabled (trip.ai.assist.enabled=true) but "
                      + "TRIP_AI_ENC_KEY is missing.\n"
                      + "This server-side secret encrypts users' AI provider API keys at "
                      + "rest (AES-256-GCM). Generate a Base64 32-byte key and set it in the "
                      + "environment, for example:\n"
                      + "  export TRIP_AI_ENC_KEY=\"$(openssl rand -base64 32)\"\n"
                      + "or disable the feature locally with TRIP_AI_ASSIST_ENABLED=false."
                );
            }
        }
    }
}
