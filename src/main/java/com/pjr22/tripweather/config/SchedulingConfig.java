package com.pjr22.tripweather.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's {@code @Scheduled} support so the guest-route / token
 * cleanup job runs on its cron. Lives in its own class so
 * {@link com.pjr22.tripweather.TripweatherApplication} stays minimal.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
