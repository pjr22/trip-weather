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
    }
}
