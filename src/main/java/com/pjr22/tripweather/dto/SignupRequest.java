package com.pjr22.tripweather.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SignupRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    private String password;

    /** Optional. Defaults to the local-part of the email when blank. */
    private String displayName;
}
