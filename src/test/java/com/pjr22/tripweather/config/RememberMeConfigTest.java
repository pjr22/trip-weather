package com.pjr22.tripweather.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;

import jakarta.servlet.http.Cookie;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression cover for the JSON-login wiring: AbstractRememberMeServices
 * checks for a {@code remember-me} HTTP form parameter inside loginSuccess(),
 * which our JSON POST will never have. The config compensates by calling
 * setAlwaysRemember(true), which makes loginSuccess() trust whoever invoked it
 * (the controller, gated on LoginRequest.rememberMe). If a future change drops
 * setAlwaysRemember, no remember-me cookie ever gets issued and users silently
 * get logged out on every backend restart.
 */
class RememberMeConfigTest {

    @Test
    void loginSuccess_issuesCookieWithoutFormParameter() {
        TokenBasedRememberMeServices services = new RememberMeConfig()
                .rememberMeServices("test-key-not-used-by-validation", new StubUserDetailsService());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        // Deliberately no remember-me form parameter — mirrors a JSON POST.
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "user@example.com", "stored-password-hash",
                AuthorityUtils.NO_AUTHORITIES);

        services.loginSuccess(request, response, auth);

        Cookie cookie = response.getCookie(RememberMeConfig.COOKIE_NAME);
        assertThat(cookie)
                .as("loginSuccess() must issue the remember-me cookie even without a form parameter")
                .isNotNull();
        assertThat(cookie.getValue()).isNotBlank();
        assertThat(cookie.getMaxAge()).isEqualTo(RememberMeConfig.DEFAULT_VALIDITY_SECONDS);
        assertThat(cookie.isHttpOnly()).isTrue();
    }

    /** Minimal UserDetailsService — TokenBased only consults it if the auth
     *  token's password is missing. We pass a password explicitly above. */
    private static class StubUserDetailsService implements UserDetailsService {
        @Override
        public UserDetails loadUserByUsername(String username) {
            return User.withUsername(username)
                    .password("stored-password-hash")
                    .authorities(AuthorityUtils.NO_AUTHORITIES)
                    .build();
        }
    }
}
