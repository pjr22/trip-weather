package com.pjr22.tripweather.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class XAdminTokenAuthenticationFilterTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void matching_header_grants_role_admin_token() throws Exception {
        XAdminTokenAuthenticationFilter filter = new XAdminTokenAuthenticationFilter("expected-token");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/refresh-coverage/colorado");
        request.addHeader(XAdminTokenAuthenticationFilter.HEADER_NAME, "expected-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            // Inspect the SecurityContext at the moment the controller would see it.
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.isAuthenticated()).isTrue();
            assertThat(auth.getAuthorities())
                    .extracting(a -> a.getAuthority())
                    .containsExactly(XAdminTokenAuthenticationFilter.ROLE_ADMIN_TOKEN);
            assertThat(auth.getAuthorities())
                    .as("token never grants ROLE_ADMIN — only the dedicated token role")
                    .extracting(a -> a.getAuthority())
                    .doesNotContain("ROLE_ADMIN");
        };

        filter.doFilter(request, response, chain);
    }

    @Test
    void missing_header_leaves_context_untouched() throws Exception {
        XAdminTokenAuthenticationFilter filter = new XAdminTokenAuthenticationFilter("expected-token");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/anything");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("missing header → the chain runs as anonymous, no auth set")
                .isNull();
    }

    @Test
    void wrong_header_value_leaves_context_untouched() throws Exception {
        XAdminTokenAuthenticationFilter filter = new XAdminTokenAuthenticationFilter("expected-token");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/refresh-coverage/colorado");
        request.addHeader(XAdminTokenAuthenticationFilter.HEADER_NAME, "wrong");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void blank_expected_token_is_fail_closed_even_when_header_present() throws Exception {
        // Operator hasn't set TRIP_ADMIN_REFRESH_TOKEN. The filter must NOT
        // grant a role just because the request happens to include any
        // header — even an empty one. Authorization layer then rejects.
        XAdminTokenAuthenticationFilter filter = new XAdminTokenAuthenticationFilter("");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/refresh-coverage/colorado");
        request.addHeader(XAdminTokenAuthenticationFilter.HEADER_NAME, "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void null_expected_token_is_fail_closed() throws Exception {
        XAdminTokenAuthenticationFilter filter = new XAdminTokenAuthenticationFilter(null);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/refresh-coverage/colorado");
        request.addHeader(XAdminTokenAuthenticationFilter.HEADER_NAME, "anything");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
