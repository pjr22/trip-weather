package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.config.SecurityConfig;
import com.pjr22.tripweather.dto.AdminLoginRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Login / logout / me endpoints for the operator console.
 *
 * <p>Authenticates against the dedicated admin {@link AuthenticationManager}
 * (built from {@code trip.admin.*} properties) so the user-table-backed manager
 * is never consulted for admin credentials. On success, persists the resulting
 * {@link SecurityContext} to the chain's admin-namespaced
 * {@link SecurityContextRepository} so the next request finds the auth.
 */
@RestController
@RequestMapping(value = "/api/admin", produces = MediaType.APPLICATION_JSON_VALUE)
@Slf4j
public class AdminAuthController {

    private final AuthenticationManager adminAuthenticationManager;
    private final SecurityContextRepository adminSecurityContextRepository;

    public AdminAuthController(
            @Qualifier("adminAuthenticationManager") AuthenticationManager adminAuthenticationManager) {
        this.adminAuthenticationManager = adminAuthenticationManager;
        // Same admin-namespaced session attribute the chain reads from
        // (SecurityConfig.ADMIN_SECURITY_CONTEXT_KEY). Two instances of
        // HttpSessionSecurityContextRepository pointing at the same key are
        // equivalent; declaring the repository as a bean would cause Spring
        // Security's auto-config to share it with the user chain too.
        this.adminSecurityContextRepository = SecurityConfig.newAdminSecurityContextRepository();
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody AdminLoginRequest request,
                                                     HttpServletRequest httpRequest,
                                                     HttpServletResponse httpResponse) {
        Authentication auth;
        try {
            UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                    request.getUsername().trim(),
                    request.getPassword());
            auth = adminAuthenticationManager.authenticate(token);
        } catch (DisabledException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(error("ADMIN_DISABLED", e.getMessage()));
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(error("INVALID_CREDENTIALS", "Username or password is incorrect."));
        }

        saveAuthentication(auth, httpRequest, httpResponse);
        return ResponseEntity.ok(Map.of(
                "username", auth.getName(),
                "role", "ADMIN"));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns the current admin principal, or 401 when no admin session exists.
     * The chain's {@code HttpStatusEntryPoint} sends the 401 for unauthenticated
     * requests; this method only fires when authentication is already in place.
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(Map.of(
                "username", auth.getName(),
                "role", "ADMIN"));
    }

    private void saveAuthentication(Authentication auth,
                                    HttpServletRequest request,
                                    HttpServletResponse response) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        // Force creation of an HTTP session so the context is persisted between
        // requests; without this the login wouldn't survive the response.
        request.getSession(true);
        // Rotate the session ID on authentication to defeat session-fixation:
        // a pre-auth session ID an attacker may have planted is no longer
        // valid after we elevate to authenticated. The user-chain session
        // (different attribute key) survives the rotation untouched — this
        // operates on the underlying JSESSIONID, not on the security context.
        try {
            request.changeSessionId();
        } catch (IllegalStateException ignore) {
            // No active session — already handled by getSession(true) above.
        }
        adminSecurityContextRepository.saveContext(context, request, response);
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("code", code);
        return body;
    }
}
