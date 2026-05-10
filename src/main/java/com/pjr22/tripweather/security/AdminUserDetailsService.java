package com.pjr22.tripweather.security;

import com.pjr22.tripweather.config.AdminAuthProperties;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

/**
 * Single-credential {@link UserDetailsService} backing the admin login. Reads
 * the username + plaintext password from {@link AdminAuthProperties}, BCrypts
 * the password once at construction, and keeps only the hash. The plaintext
 * is never stored on the instance and never logged.
 *
 * <p>Phase 0 of ADMIN_CONSOLE.md. Deliberately not a Spring bean — instantiated
 * only from {@code SecurityConfig}'s admin-chain wiring so it cannot be picked
 * up by the auto-configured {@code AuthenticationManager} that the user chain
 * uses.
 */
public class AdminUserDetailsService implements UserDetailsService {

    /** Granted to the admin-login principal (and only that principal). */
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final String username;
    private final String hashedPassword;

    public AdminUserDetailsService(AdminAuthProperties props, PasswordEncoder encoder) {
        if (props.username() == null || props.username().isBlank()) {
            throw new IllegalStateException(
                    "AdminUserDetailsService cannot be constructed when trip.admin.username is blank.");
        }
        if (props.password() == null || props.password().isBlank()) {
            throw new IllegalStateException(
                    "AdminUserDetailsService cannot be constructed when trip.admin.password is blank.");
        }
        this.username = props.username().trim();
        this.hashedPassword = encoder.encode(props.password());
    }

    @Override
    public UserDetails loadUserByUsername(String name) throws UsernameNotFoundException {
        // Constant-string compare: Spring's BadCredentialsException leaks no
        // username vs password distinction, but answering "no such user"
        // faster than "wrong password" is itself a side channel. equals()
        // here is OK — the admin username is a fixed config value, not data
        // an attacker can probe to time. The password hash check downstream
        // is the constant-time comparator.
        if (!this.username.equals(name)) {
            throw new UsernameNotFoundException("Unknown admin username");
        }
        return User.withUsername(this.username)
                .password(this.hashedPassword)
                .authorities(List.of(new SimpleGrantedAuthority(ROLE_ADMIN)))
                .build();
    }
}
