package com.pjr22.tripweather.service;

import com.pjr22.tripweather.model.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Sends transactional email via Mailtrap's REST sending API.
 *
 * Templates live under {@code src/main/resources/templates/email/} as plain
 * HTML with {@code {{placeholder}}} substitution — simple {@code String.replace}
 * is enough for v1; swap in Thymeleaf later if templates grow more complex.
 *
 * When {@code trip.email.enabled=false} the call is short-circuited and the
 * payload is logged at INFO instead of being sent — useful in local dev when
 * no Mailtrap inbox is configured.
 */
@Service
@Slf4j
public class EmailService {

    private final RestClient restClient;
    private final String emailUrl;
    private final String apiKey;
    private final String fromAddress;
    private final String fromName;
    private final boolean enabled;

    public EmailService(@Value("${trip.email.url}") String emailUrl,
                        @Value("${trip.email.api-key}") String apiKey,
                        @Value("${trip.email.from}") String fromAddress,
                        @Value("${trip.email.from-name}") String fromName,
                        @Value("${trip.email.enabled}") boolean enabled) {
        this.emailUrl = emailUrl;
        this.apiKey = apiKey;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
        this.enabled = enabled;
        this.restClient = RestClient.create();
    }

    public void sendVerificationEmail(User user, String token, String baseUrl, long lifetimeMinutes) {
        String verificationUrl = baseUrl + "/verify?token=" + token;
        String lifetimeText = humanizeMinutes(lifetimeMinutes);
        String html = renderTemplate("templates/email/verification.html", Map.of(
                "displayName", safeDisplayName(user),
                "verificationUrl", verificationUrl,
                "lifetime", lifetimeText
        ));
        String textFallback = "Hi " + safeDisplayName(user) + ",\n\n"
                + "Click the link below to verify your Trip Weather email:\n\n"
                + verificationUrl + "\n\n"
                + "This link expires in " + lifetimeText + ".\n";
        send(user.getEmail(), safeDisplayName(user),
                "Verify your Trip Weather email", textFallback, html);
    }

    public void sendPasswordResetEmail(User user, String token, String baseUrl, long lifetimeMinutes) {
        String resetUrl = baseUrl + "/reset-password?token=" + token;
        String lifetimeText = humanizeMinutes(lifetimeMinutes);
        String html = renderTemplate("templates/email/password-reset.html", Map.of(
                "displayName", safeDisplayName(user),
                "resetUrl", resetUrl,
                "lifetime", lifetimeText
        ));
        String textFallback = "Hi " + safeDisplayName(user) + ",\n\n"
                + "We received a request to reset the password on your Trip Weather account.\n"
                + "Click the link below to choose a new one:\n\n"
                + resetUrl + "\n\n"
                + "This link expires in " + lifetimeText + ". If you didn't request this, you can ignore this email.\n";
        send(user.getEmail(), safeDisplayName(user),
                "Reset your Trip Weather password", textFallback, html);
    }

    /**
     * Format a minute count for human-readable email copy. Renders whole-hour
     * values as "N hour(s)" and falls back to "N minute(s)" otherwise. Keeps
     * the email phrasing natural for typical configured values (5, 15, 60,
     * 1440 minutes) without bringing in a Duration formatter library.
     */
    static String humanizeMinutes(long minutes) {
        if (minutes <= 0) {
            return "0 minutes";
        }
        if (minutes < 60 || minutes % 60 != 0) {
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        long hours = minutes / 60;
        return hours + (hours == 1 ? " hour" : " hours");
    }

    private void send(String toEmail, String toName, String subject, String text, String html) {
        if (!enabled) {
            // Dev-friendly: dump the text body too, so the developer can copy
            // the verification URL directly out of the log instead of needing
            // a Mailtrap inbox. Don't do this when enabled=true — tokens
            // shouldn't appear in production logs.
            log.info("[email-disabled] Would send to={} subject='{}'\n--- text body ---\n{}\n-----------------",
                    toEmail, subject, text);
            return;
        }
        Map<String, Object> body = Map.of(
                "from", Map.of("email", fromAddress, "name", fromName),
                "to", List.of(Map.of("email", toEmail, "name", toName)),
                "subject", subject,
                "text", text,
                "html", html
        );
        try {
            restClient.post()
                    .uri(emailUrl)
                    .header("Api-Token", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Email sent to={} subject='{}'", toEmail, subject);
        } catch (Exception e) {
            // Mail failures shouldn't poison the calling transaction. The user
            // can request a fresh verification email via "Resend".
            log.error("Failed to send email to={} subject='{}': {}", toEmail, subject, e.getMessage(), e);
        }
    }

    private String renderTemplate(String classpathLocation, Map<String, String> values) {
        try {
            ClassPathResource resource = new ClassPathResource(classpathLocation);
            String template = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            for (Map.Entry<String, String> entry : values.entrySet()) {
                template = template.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }
            return template;
        } catch (IOException e) {
            throw new IllegalStateException("Email template not found: " + classpathLocation, e);
        }
    }

    private static String safeDisplayName(User user) {
        return user.getName() != null && !user.getName().isBlank() ? user.getName() : user.getEmail();
    }
}
