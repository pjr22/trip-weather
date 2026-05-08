package com.pjr22.tripweather.service;

import com.pjr22.tripweather.model.EmailVerification;
import com.pjr22.tripweather.model.PasswordReset;
import com.pjr22.tripweather.model.User;
import com.pjr22.tripweather.repository.EmailVerificationRepository;
import com.pjr22.tripweather.repository.PasswordResetRepository;
import com.pjr22.tripweather.repository.UserRepository;
import com.pjr22.tripweather.security.TokenUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Self-service account flows: signup, email verification, resend, plus the
 * Phase 4 forgot-/reset-/change-password and delete-account flows.
 *
 * Public methods deliberately don't reveal whether a given email is registered
 * (no enumeration leaks); that policy is enforced here, not in the controller,
 * so any future caller benefits without re-implementing it.
 */
@Service
@Slf4j
public class UserAccountService {

    /** Min password length per plan §7.1 — keep simple, no character-class rules. */
    public static final int MIN_PASSWORD_LENGTH = 12;

    private final UserRepository userRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final PasswordResetRepository passwordResetRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final String baseUrl;
    private final long emailTokenLifetimeMinutes;

    public UserAccountService(UserRepository userRepository,
                              EmailVerificationRepository emailVerificationRepository,
                              PasswordResetRepository passwordResetRepository,
                              EmailService emailService,
                              PasswordEncoder passwordEncoder,
                              @Value("${trip.app.base-url}") String baseUrl,
                              @Value("${trip.auth.email-token.lifetime-minutes}") long emailTokenLifetimeMinutes) {
        this.userRepository = userRepository;
        this.emailVerificationRepository = emailVerificationRepository;
        this.passwordResetRepository = passwordResetRepository;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.emailTokenLifetimeMinutes = emailTokenLifetimeMinutes;
    }

    /**
     * Outcome of a signup attempt. The controller maps each value to a
     * specific HTTP response so the SPA can show an appropriate UX.
     *
     * <p>Phase 2 originally returned the same response for every signup
     * regardless of whether the email was new or existing (anti-enumeration).
     * Phase 4 relaxed that for verified-account collisions: signing up with
     * an email already tied to a verified account now surfaces a clear "log
     * in or reset your password" prompt instead of silently dead-ending in
     * a "check your email" modal that never produces an email.
     */
    public enum SignupResult {
        /** Brand-new user; verification email sent. */
        CREATED,
        /** Existing user that hasn't verified yet; prior tokens invalidated, fresh verification email sent. */
        RESENT_VERIFICATION,
        /** Existing user that has already verified — caller should redirect to login / forgot-password. */
        ALREADY_VERIFIED
    }

    /**
     * Create a disabled user, mint a verification token, and send the email.
     *
     * <p>If the address belongs to an existing-but-not-yet-verified user this
     * acts like resend-verification: any prior unconsumed token is invalidated
     * and a fresh one is emailed. If the address belongs to a verified user
     * the call returns {@link SignupResult#ALREADY_VERIFIED} without touching
     * the account so the caller can show a "use your existing account"
     * prompt.
     */
    @Transactional
    public SignupResult signup(String emailRaw, String password, String displayName) {
        validatePassword(password);
        String email = normalizeEmail(emailRaw);

        Optional<User> existing = userRepository.findByEmailIgnoreCase(email);
        if (existing.isPresent()) {
            User existingUser = existing.get();
            if (existingUser.isEnabled()) {
                log.info("Signup attempt for already-verified email; surfacing collision.");
                return SignupResult.ALREADY_VERIFIED;
            }
            // Pending account — treat the signup as a "resend verification"
            // so the user gets a usable link, but DON'T overwrite their
            // password hash or display name (that would let anyone change
            // those fields on someone else's pending account).
            invalidateOpenVerifications(existingUser);
            String rawToken = createVerificationToken(existingUser);
            emailService.sendVerificationEmail(existingUser, rawToken, baseUrl, emailTokenLifetimeMinutes);
            log.info("Signup attempt for pending account; resent verification token.");
            return SignupResult.RESENT_VERIFICATION;
        }

        User user = new User();
        user.setEmail(email);
        user.setName(resolveDisplayName(displayName, email));
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setEnabled(false);
        user = userRepository.save(user);

        String rawToken = createVerificationToken(user);
        emailService.sendVerificationEmail(user, rawToken, baseUrl, emailTokenLifetimeMinutes);
        log.info("Signup recorded for new user; verification token issued.");
        return SignupResult.CREATED;
    }

    /**
     * Resolve a verification token. Returns the now-enabled user on success so
     * the controller can establish a session ("auto-login on verify"). Throws
     * {@link InvalidTokenException} when the token is unknown, expired, or
     * already consumed — controller maps this to a single user-facing error.
     */
    @Transactional
    public User verifyEmail(String rawToken) {
        EmailVerification verification = emailVerificationRepository
                .findByTokenHash(TokenUtil.hash(rawToken))
                .orElseThrow(() -> new InvalidTokenException("Verification token not found"));

        LocalDateTime now = LocalDateTime.now();
        if (verification.getConsumedAt() != null) {
            throw new InvalidTokenException("Verification token already consumed");
        }
        if (verification.getExpiresAt().isBefore(now)) {
            throw new InvalidTokenException("Verification token expired");
        }

        verification.setConsumedAt(now);
        emailVerificationRepository.save(verification);

        User user = verification.getUser();
        user.setEnabled(true);
        return userRepository.save(user);
    }

    /**
     * Issue a fresh verification email if the address belongs to a user who
     * hasn't verified yet. Always returns silently to avoid enumeration —
     * unknown addresses and already-verified accounts produce no observable
     * difference. Any prior unconsumed tokens for this user are invalidated
     * so a stolen old link can't be replayed.
     */
    @Transactional
    public void resendVerification(String emailRaw) {
        String email = normalizeEmail(emailRaw);
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(email);
        if (userOpt.isEmpty()) {
            log.info("Resend requested for unknown email; not sending.");
            return;
        }
        User user = userOpt.get();
        if (user.isEnabled()) {
            log.info("Resend requested for already-verified user; not sending.");
            return;
        }
        invalidateOpenVerifications(user);
        String rawToken = createVerificationToken(user);
        emailService.sendVerificationEmail(user, rawToken, baseUrl, emailTokenLifetimeMinutes);
        log.info("Resent verification token to user.");
    }

    /**
     * Issue a password-reset email if the address belongs to a verified
     * account. Always returns silently — unknown addresses, unverified
     * accounts, and the guest sentinel produce no observable difference.
     * Any prior unconsumed reset tokens for this user are invalidated so a
     * stolen old link can't be replayed alongside a freshly-requested one.
     */
    @Transactional
    public void forgotPassword(String emailRaw) {
        String email = normalizeEmail(emailRaw);
        Optional<User> userOpt = userRepository.findByEmailIgnoreCase(email);
        if (userOpt.isEmpty()) {
            log.info("Password reset requested for unknown email; not sending.");
            return;
        }
        User user = userOpt.get();
        if (!user.isEnabled() || user.getPasswordHash() == null) {
            // Guest-style accounts (no password) and unverified accounts can't
            // reset what they don't have — bounce silently to avoid telling
            // the caller anything about the account state.
            log.info("Password reset requested for an account that can't be reset; not sending.");
            return;
        }
        invalidateOpenPasswordResets(user);
        String rawToken = createPasswordResetToken(user);
        emailService.sendPasswordResetEmail(user, rawToken, baseUrl, emailTokenLifetimeMinutes);
        log.info("Password reset token issued.");
    }

    /**
     * Apply a password-reset token: set a new password hash and mark the token
     * consumed. The new hash automatically invalidates every browser's
     * remember-me cookie for this user — those cookies' signatures are
     * computed over the password hash, so any change breaks them. Throws
     * {@link InvalidTokenException} on unknown / expired / consumed tokens;
     * the controller maps all of these to the same user-facing error.
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        validatePassword(newPassword);
        PasswordReset reset = passwordResetRepository
                .findByTokenHash(TokenUtil.hash(rawToken))
                .orElseThrow(() -> new InvalidTokenException("Reset token not found"));

        LocalDateTime now = LocalDateTime.now();
        if (reset.getConsumedAt() != null) {
            throw new InvalidTokenException("Reset token already consumed");
        }
        if (reset.getExpiresAt().isBefore(now)) {
            throw new InvalidTokenException("Reset token expired");
        }

        reset.setConsumedAt(now);
        passwordResetRepository.save(reset);

        User user = reset.getUser();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // If the account was enabled=false because verification got lost, a
        // successful reset proves they own the inbox — flip enabled on, same
        // as the verify flow. Harmless when already enabled.
        user.setEnabled(true);
        userRepository.save(user);

        // Invalidate any other still-open reset tokens too, so a coordinated
        // attacker who minted a second token in flight can't use it now.
        invalidateOpenPasswordResets(user);
        log.info("Password reset completed for user {}.", user.getId());
    }

    /**
     * Change the password of the currently-authenticated user. The caller's
     * current password is checked against the stored hash before anything
     * changes. The new password hash automatically invalidates every browser's
     * remember-me cookie for this user (the signature is computed over the
     * hash), so other browsers fall back to plain auth on their next request.
     */
    @Transactional
    public void changePassword(String email, String currentPassword, String newPassword) {
        validatePassword(newPassword);
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated user not found in database: " + email));

        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException("Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Any pending password-reset email links should stop working too.
        invalidateOpenPasswordResets(user);
        log.info("Password changed for user {}.", user.getId());
    }

    /**
     * Delete the currently-authenticated user's account. The caller's password
     * is required so a stolen session cookie alone can't trigger a delete.
     * Routes / waypoints / verification + reset tokens cascade away via the
     * FK ON DELETE CASCADE established in Phase 1. Remember-me cookies are
     * stateless (token-based, signed against the password hash) — the now-gone
     * user fails {@code loadUserByUsername} on their next request, so every
     * browser's cookie is implicitly invalid.
     */
    @Transactional
    public void deleteAccount(String email, String currentPassword) {
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated user not found in database: " + email));

        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException("Current password is incorrect");
        }

        userRepository.delete(user);
        log.info("Account deleted for user {}.", user.getId());
    }

    private String createVerificationToken(User user) {
        String rawToken = TokenUtil.newToken();
        EmailVerification ev = new EmailVerification();
        ev.setUser(user);
        ev.setTokenHash(TokenUtil.hash(rawToken));
        ev.setExpiresAt(LocalDateTime.now().plusMinutes(emailTokenLifetimeMinutes));
        emailVerificationRepository.save(ev);
        return rawToken;
    }

    private String createPasswordResetToken(User user) {
        String rawToken = TokenUtil.newToken();
        PasswordReset reset = new PasswordReset();
        reset.setUser(user);
        reset.setTokenHash(TokenUtil.hash(rawToken));
        reset.setExpiresAt(LocalDateTime.now().plusMinutes(emailTokenLifetimeMinutes));
        passwordResetRepository.save(reset);
        return rawToken;
    }

    private void invalidateOpenVerifications(User user) {
        emailVerificationRepository.consumeOpenForUser(user.getId(), LocalDateTime.now());
    }

    private void invalidateOpenPasswordResets(User user) {
        passwordResetRepository.consumeOpenForUser(user.getId(), LocalDateTime.now());
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new InvalidPasswordException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
    }

    private static String normalizeEmail(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Email is required");
        }
        String trimmed = raw.trim().toLowerCase();
        if (trimmed.isEmpty() || !trimmed.contains("@")) {
            throw new IllegalArgumentException("Invalid email address");
        }
        return trimmed;
    }

    private static String resolveDisplayName(String requested, String email) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) { super(message); }
    }

    public static class InvalidPasswordException extends RuntimeException {
        public InvalidPasswordException(String message) { super(message); }
    }

    /** Re-auth failed inside change-password / delete-account. */
    public static class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException(String message) { super(message); }
    }
}
