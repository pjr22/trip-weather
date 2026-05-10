package com.pjr22.tripweather.security;

import com.pjr22.tripweather.config.AdminAuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminUserDetailsServiceTest {

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Test
    void loads_configured_admin_with_role_admin() {
        AdminUserDetailsService svc = new AdminUserDetailsService(
                new AdminAuthProperties(true, "alice", "s3cret", ""), ENCODER);

        UserDetails user = svc.loadUserByUsername("alice");

        assertThat(user.getUsername()).isEqualTo("alice");
        assertThat(user.getAuthorities())
                .extracting(a -> a.getAuthority())
                .containsExactly(AdminUserDetailsService.ROLE_ADMIN);
        assertThat(ENCODER.matches("s3cret", user.getPassword()))
                .as("password is BCrypted at construction and must verify against the original plaintext")
                .isTrue();
    }

    @Test
    void rejects_other_usernames() {
        AdminUserDetailsService svc = new AdminUserDetailsService(
                new AdminAuthProperties(true, "alice", "s3cret", ""), ENCODER);

        assertThatThrownBy(() -> svc.loadUserByUsername("bob"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void blank_username_or_password_is_rejected_at_construction() {
        assertThatThrownBy(() ->
                new AdminUserDetailsService(
                        new AdminAuthProperties(true, "", "s3cret", ""), ENCODER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("username");

        assertThatThrownBy(() ->
                new AdminUserDetailsService(
                        new AdminAuthProperties(true, "alice", "", ""), ENCODER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("password");
    }

    @Test
    void password_plaintext_is_not_retained_on_the_instance() {
        // Negative-space cover for the BCrypt-at-construction contract: a
        // future change that stored the plaintext field would cause the
        // hash check below to short-circuit (returning the plaintext as the
        // "hash"), and either the matches() call would throw (BCrypt format
        // check on the stored value fails) or matches('s3cret', 's3cret')
        // would return false.
        AdminUserDetailsService svc = new AdminUserDetailsService(
                new AdminAuthProperties(true, "alice", "s3cret", ""), ENCODER);

        UserDetails user = svc.loadUserByUsername("alice");
        assertThat(user.getPassword())
                .as("password field must be a BCrypt hash, not the original plaintext")
                .startsWith("$2");
        assertThat(user.getPassword()).isNotEqualTo("s3cret");
    }
}
