package com.pjr22.tripweather.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Body of {@code POST /api/admin/login}. Username is a free-form string (the
 * configured {@code trip.admin.username}) — not necessarily an email — so this
 * DTO deliberately omits the {@code @Email} constraint that the regular user
 * {@link LoginRequest} imposes.
 */
@Data
public class AdminLoginRequest {

    @NotBlank
    private String username;

    @NotBlank
    private String password;
}
