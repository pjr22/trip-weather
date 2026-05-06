package com.pjr22.tripweather.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String password;

    /**
     * "Stay logged in for 30 days" — when true and credentials check out, the
     * controller asks the persistent-token remember-me services to issue a
     * cookie. Defaults to false so the existing session-only behaviour is
     * preserved when the SPA omits the field.
     */
    private boolean rememberMe;
}
