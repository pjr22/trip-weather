package com.pjr22.tripweather.controller;

import com.pjr22.tripweather.dto.ChangePasswordRequest;
import com.pjr22.tripweather.dto.DeleteAccountRequest;
import com.pjr22.tripweather.dto.ForgotPasswordRequest;
import com.pjr22.tripweather.dto.LoginRequest;
import com.pjr22.tripweather.dto.ResendVerificationRequest;
import com.pjr22.tripweather.dto.ResetPasswordRequest;
import com.pjr22.tripweather.dto.SignupRequest;
import com.pjr22.tripweather.dto.UserDto;
import com.pjr22.tripweather.dto.VerifyRequest;
import com.pjr22.tripweather.model.User;
import com.pjr22.tripweather.security.CurrentUserService;
import com.pjr22.tripweather.service.UserAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping(value = "/api/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Slf4j
public class AuthController {

    private final CurrentUserService currentUserService;
    private final UserAccountService userAccountService;
    private final AuthenticationManager authenticationManager;
    private final ObjectProvider<AbstractRememberMeServices> rememberMeServicesProvider;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public AuthController(CurrentUserService currentUserService,
                          UserAccountService userAccountService,
                          AuthenticationManager authenticationManager,
                          ObjectProvider<AbstractRememberMeServices> rememberMeServicesProvider) {
        this.currentUserService = currentUserService;
        this.userAccountService = userAccountService;
        this.authenticationManager = authenticationManager;
        this.rememberMeServicesProvider = rememberMeServicesProvider;
    }

    /**
     * Returns the authenticated user, or {@code {"user": null}} for anonymous
     * sessions. The SPA calls this on page load to render the right header
     * state.
     */
    @GetMapping("/me")
    public Map<String, Object> me() {
        return currentUserService.currentUser()
                .map(UserDto::from)
                .map(dto -> Collections.<String, Object>singletonMap("user", dto))
                .orElseGet(() -> Collections.singletonMap("user", null));
    }

    @PostMapping("/signup")
    public ResponseEntity<Map<String, Object>> signup(@Valid @RequestBody SignupRequest request) {
        UserAccountService.SignupResult result;
        try {
            result = userAccountService.signup(
                    request.getEmail(), request.getPassword(), request.getDisplayName());
        } catch (UserAccountService.InvalidPasswordException e) {
            return ResponseEntity.badRequest().body(error("INVALID_PASSWORD", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error("INVALID_INPUT", e.getMessage()));
        }
        // CREATED and RESENT_VERIFICATION share the same UX: in both cases
        // the user can finish setup by clicking the verification link in
        // their inbox. ALREADY_VERIFIED is the new collision branch — caller
        // should offer log-in / forgot-password instead of a dead-end "check
        // your email" modal that produces no email.
        return switch (result) {
            case CREATED, RESENT_VERIFICATION -> ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(Map.of("message", "Check your email for a verification link."));
            case ALREADY_VERIFIED -> ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(error("EMAIL_ALREADY_REGISTERED",
                            "That email is already registered. Try logging in, or reset your password if you've forgotten it."));
        };
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(@Valid @RequestBody VerifyRequest request,
                                                      HttpServletRequest httpRequest,
                                                      HttpServletResponse httpResponse) {
        User user;
        try {
            user = userAccountService.verifyEmail(request.getToken());
        } catch (UserAccountService.InvalidTokenException e) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(error("INVALID_TOKEN", "This verification link is invalid or expired."));
        }
        // Auto-login: build an authenticated principal directly and persist the
        // resulting SecurityContext into the HTTP session so the next request
        // is recognised as logged-in. This bypasses the AuthenticationManager
        // because we trust the verification token; the password isn't in hand.
        establishSession(user.getEmail(), httpRequest, httpResponse);
        return ResponseEntity.ok(Map.of("user", UserDto.from(user)));
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<Map<String, Object>> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        userAccountService.resendVerification(request.getEmail());
        // Always 200 regardless of whether the email is registered or already verified.
        return ResponseEntity.ok(Map.of("message", "If that address can receive a verification email, one has been sent."));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request,
                                                     HttpServletRequest httpRequest,
                                                     HttpServletResponse httpResponse) {
        Authentication auth;
        try {
            UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                    request.getEmail().trim().toLowerCase(),
                    request.getPassword());
            auth = authenticationManager.authenticate(token);
            saveAuthentication(auth, httpRequest, httpResponse);
        } catch (DisabledException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(error("EMAIL_NOT_VERIFIED",
                            "Please verify your email before logging in."));
        } catch (BadCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(error("INVALID_CREDENTIALS", "Email or password is incorrect."));
        }

        // Issue the persistent-login cookie only when the user opted in. A
        // missing services bean (feature disabled) silently no-ops so dev
        // flows without TRIP_REMEMBER_ME_KEY still log users in for the
        // session.
        if (request.isRememberMe()) {
            AbstractRememberMeServices rememberMeServices = rememberMeServicesProvider.getIfAvailable();
            if (rememberMeServices != null) {
                rememberMeServices.loginSuccess(httpRequest, httpResponse, auth);
            } else {
                log.warn("Login requested rememberMe but the feature is disabled (no rememberMeServices bean).");
            }
        }

        User user = currentUserService.currentUser()
                .orElseThrow(() -> new IllegalStateException("Just authenticated but no current user"));
        return ResponseEntity.ok(Map.of("user", UserDto.from(user)));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        // Invalidate any remember-me cookie tied to this browser as well as
        // the session — otherwise the next request would re-auth from the
        // cookie and immediately log the user back in. Spring's
        // AbstractRememberMeServices.logout() cancels the cookie.
        AbstractRememberMeServices rememberMeServices = rememberMeServicesProvider.getIfAvailable();
        if (rememberMeServices != null) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            rememberMeServices.logout(request, response, auth);
        }
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, Object>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        try {
            userAccountService.forgotPassword(request.getEmail());
        } catch (IllegalArgumentException e) {
            // Same shape as the success path — don't tell the caller their
            // email failed normalization either, that's an enumeration channel.
            log.info("forgot-password rejected at validation: {}", e.getMessage());
        }
        return ResponseEntity.ok(Map.of(
                "message", "If that address is registered, we've sent a password-reset link."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword(@Valid @RequestBody ResetPasswordRequest request,
                                                             HttpServletRequest httpRequest,
                                                             HttpServletResponse httpResponse) {
        try {
            userAccountService.resetPassword(request.getToken(), request.getNewPassword());
        } catch (UserAccountService.InvalidTokenException e) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(error("INVALID_TOKEN", "This reset link is invalid or expired."));
        } catch (UserAccountService.InvalidPasswordException e) {
            return ResponseEntity.badRequest()
                    .body(error("INVALID_PASSWORD", e.getMessage()));
        }
        // Don't auto-login — per Phase 4 decision, send the user to the login
        // modal with their new password. Clear any current session so a stale
        // tab can't keep going as the now-old user, and clear the cookie tied
        // to this browser. Other browsers' cookies were invalidated implicitly
        // when the service rewrote the password hash (the cookie's signature
        // is computed over the hash, so any change to it breaks every cookie).
        AbstractRememberMeServices rememberMeServices = rememberMeServicesProvider.getIfAvailable();
        if (rememberMeServices != null) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            rememberMeServices.logout(httpRequest, httpResponse, auth);
        }
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                                              HttpServletRequest httpRequest,
                                                              HttpServletResponse httpResponse) {
        User user = currentUserService.currentUser()
                .orElseThrow(() -> new IllegalStateException(
                        "@authenticated route reached without a current user"));
        try {
            userAccountService.changePassword(user.getEmail(),
                    request.getCurrentPassword(), request.getNewPassword());
        } catch (UserAccountService.InvalidCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(error("INVALID_CREDENTIALS", "Current password is incorrect."));
        } catch (UserAccountService.InvalidPasswordException e) {
            return ResponseEntity.badRequest()
                    .body(error("INVALID_PASSWORD", e.getMessage()));
        }
        // The password change rewrote the password hash, which is part of every
        // remember-me cookie's signature — so every browser's cookie is now
        // invalid. The current tab is still authenticated via its session, so
        // the user keeps using the app, but their remember-me cookie no longer
        // works. Clear it explicitly so it isn't sent on a future request only
        // to be rejected; the user can tick "stay logged in" again at next login.
        AbstractRememberMeServices rememberMeServices = rememberMeServicesProvider.getIfAvailable();
        if (rememberMeServices != null) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            rememberMeServices.logout(httpRequest, httpResponse, auth);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/delete-account")
    public ResponseEntity<Map<String, Object>> deleteAccount(@Valid @RequestBody DeleteAccountRequest request,
                                                             HttpServletRequest httpRequest,
                                                             HttpServletResponse httpResponse) {
        User user = currentUserService.currentUser()
                .orElseThrow(() -> new IllegalStateException(
                        "@authenticated route reached without a current user"));
        try {
            userAccountService.deleteAccount(user.getEmail(), request.getCurrentPassword());
        } catch (UserAccountService.InvalidCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(error("INVALID_CREDENTIALS", "Current password is incorrect."));
        }
        // Clear the cookie on the current browser too. The user row is gone,
        // so loadUserByUsername would reject every browser's cookie anyway —
        // this just stops the now-orphan cookie from being sent.
        AbstractRememberMeServices rememberMeServices = rememberMeServicesProvider.getIfAvailable();
        if (rememberMeServices != null) {
            rememberMeServices.logout(httpRequest, httpResponse, null);
        }
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    private void establishSession(String email,
                                  HttpServletRequest request,
                                  HttpServletResponse response) {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                email, null, AuthorityUtils.NO_AUTHORITIES);
        saveAuthentication(auth, request, response);
    }

    private void saveAuthentication(Authentication auth,
                                    HttpServletRequest request,
                                    HttpServletResponse response) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        // Force creation of an HTTP session so the context is persisted between
        // requests; without this the auto-login wouldn't survive the response.
        request.getSession(true);
        // Rotate the session ID on authentication to defeat session-fixation:
        // a pre-auth session ID an attacker may have planted is no longer
        // valid after we elevate to authenticated. The CSRF token lives in a
        // cookie (CookieCsrfTokenRepository) — not bound to the session — so
        // rotation doesn't disturb it.
        try {
            request.changeSessionId();
        } catch (IllegalStateException ignore) {
            // No active session — already handled by getSession(true) above.
        }
        securityContextRepository.saveContext(context, request, response);
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("code", code);
        return body;
    }
}
