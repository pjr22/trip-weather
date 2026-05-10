package com.pjr22.tripweather.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Static-asset URL conveniences for the admin console.
 *
 * <p>Spring Boot's {@code WelcomePageHandlerMapping} only resolves
 * {@code index.html} for the application root ({@code /}); it does not do
 * directory-index resolution for subfolders. Without these mappings, hitting
 * {@code /admin/} after a successful login would 404 (because the static
 * resource handler is asked for a directory, not a file). The forward keeps
 * the URL the user sees; the redirect normalises the no-slash variant.
 *
 * <p>Both URLs flow through the admin {@link
 * org.springframework.security.web.SecurityFilterChain SecurityFilterChain}
 * before MVC sees them, so anonymous callers still land on
 * {@code /admin/login.html} via the chain's authentication entry point.
 * Phase 0 of ADMIN_CONSOLE.md.
 */
@Configuration
public class AdminWebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
        registry.addViewController("/admin").setViewName("redirect:/admin/");
    }
}
