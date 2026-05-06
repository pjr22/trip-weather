package com.pjr22.tripweather.dto;

import com.pjr22.tripweather.model.User;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Public projection of a {@link User} for the SPA's /api/auth/me response.
 * Excludes password hash and any internal flags the client doesn't need.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {

    private UUID id;
    private String email;
    private String displayName;

    public static UserDto from(User user) {
        return new UserDto(user.getId(), user.getEmail(), user.getName());
    }
}
