package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.config.AdminAuthProperties;
import com.pjr22.tripweather.config.SecurityConfig;
import com.pjr22.tripweather.dto.AdminLoginRequest;
import com.pjr22.tripweather.security.AdminUserDetailsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAuthControllerTest {

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static AuthenticationManager realManagerFor(String username, String password) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(
                new AdminUserDetailsService(
                        new AdminAuthProperties(true, username, password, ""), ENCODER));
        provider.setPasswordEncoder(ENCODER);
        return new ProviderManager(provider);
    }

    @Test
    void login_with_correct_credentials_persists_admin_context_under_admin_key() {
        AdminAuthController controller = new AdminAuthController(realManagerFor("alice", "s3cret"));
        AdminLoginRequest request = new AdminLoginRequest();
        request.setUsername("alice");
        request.setPassword("s3cret");
        MockHttpServletRequest httpRequest = new MockHttpServletRequest("POST", "/api/admin/login");
        MockHttpServletResponse httpResponse = new MockHttpServletResponse();

        ResponseEntity<Map<String, Object>> response = controller.login(request, httpRequest, httpResponse);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("username", "alice");
        assertThat(response.getBody()).containsEntry("role", "ADMIN");

        // Critical invariant: the admin chain reads the SecurityContext from
        // the admin-namespaced session attribute (ADMIN_SECURITY_CONTEXT_KEY),
        // and the controller must write to the same key. If these drift,
        // login appears to succeed but the next request comes through
        // unauthenticated.
        SecurityContextRepository repo = SecurityConfig.newAdminSecurityContextRepository();
        SecurityContext loaded = repo.loadDeferredContext(httpRequest).get();
        Authentication auth = loaded.getAuthentication();
        assertThat(auth)
                .as("admin context must be persisted under SecurityConfig.ADMIN_SECURITY_CONTEXT_KEY")
                .isNotNull();
        assertThat(auth.getName()).isEqualTo("alice");
        assertThat(auth.getAuthorities())
                .extracting(a -> a.getAuthority())
                .containsExactly(AdminUserDetailsService.ROLE_ADMIN);

        // The default (user-chain) session attribute MUST be untouched.
        // Defence against a regression that drops the custom key, which
        // would let the admin principal authenticate user-chain endpoints.
        SecurityContext userChainContext = (SecurityContext)
                httpRequest.getSession(false).getAttribute(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(userChainContext)
                .as("admin login must not write to the default user-chain context attribute")
                .isNull();
    }

    @Test
    void login_with_wrong_password_returns_401() {
        AdminAuthController controller = new AdminAuthController(realManagerFor("alice", "s3cret"));
        AdminLoginRequest request = new AdminLoginRequest();
        request.setUsername("alice");
        request.setPassword("wrong");
        MockHttpServletRequest httpRequest = new MockHttpServletRequest("POST", "/api/admin/login");
        MockHttpServletResponse httpResponse = new MockHttpServletResponse();

        ResponseEntity<Map<String, Object>> response = controller.login(request, httpRequest, httpResponse);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("code", "INVALID_CREDENTIALS");
    }

    @Test
    void login_with_unknown_username_returns_401() {
        AdminAuthController controller = new AdminAuthController(realManagerFor("alice", "s3cret"));
        AdminLoginRequest request = new AdminLoginRequest();
        request.setUsername("bob");
        request.setPassword("s3cret");
        MockHttpServletRequest httpRequest = new MockHttpServletRequest("POST", "/api/admin/login");
        MockHttpServletResponse httpResponse = new MockHttpServletResponse();

        ResponseEntity<Map<String, Object>> response = controller.login(request, httpRequest, httpResponse);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("code", "INVALID_CREDENTIALS");
    }

    @Test
    void login_when_admin_disabled_returns_403() {
        AuthenticationManager disabledManager = authentication -> {
            throw new org.springframework.security.authentication.DisabledException(
                    "Admin login is disabled (trip.admin.enabled=false)");
        };
        AdminAuthController controller = new AdminAuthController(disabledManager);
        AdminLoginRequest request = new AdminLoginRequest();
        request.setUsername("alice");
        request.setPassword("s3cret");

        ResponseEntity<Map<String, Object>> response = controller.login(
                request, new MockHttpServletRequest("POST", "/api/admin/login"), new MockHttpServletResponse());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("code", "ADMIN_DISABLED");
    }

    @Test
    void logout_invalidates_session_and_clears_context() {
        AdminAuthController controller = new AdminAuthController(realManagerFor("alice", "s3cret"));
        MockHttpServletRequest httpRequest = new MockHttpServletRequest("POST", "/api/admin/logout");
        // Stand up a session as though a prior login had populated it.
        httpRequest.getSession(true).setAttribute(SecurityConfig.ADMIN_SECURITY_CONTEXT_KEY, "anything");

        ResponseEntity<Void> response = controller.logout(httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(httpRequest.getSession(false))
                .as("session must be invalidated by logout")
                .isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("SecurityContextHolder must be cleared")
                .isNull();
    }

    @Test
    void me_returns_authenticated_principal_when_context_holds_admin() {
        // Mimic the chain having loaded the admin context onto the holder.
        Authentication adminAuth =
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                        .authenticated("alice", null,
                                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                        AdminUserDetailsService.ROLE_ADMIN)));
        SecurityContextHolder.getContext().setAuthentication(adminAuth);

        AdminAuthController controller = new AdminAuthController(realManagerFor("alice", "s3cret"));

        ResponseEntity<Map<String, Object>> response = controller.me();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("username", "alice");
        assertThat(response.getBody()).containsEntry("role", "ADMIN");
    }
}
