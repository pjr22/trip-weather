package com.pjr22.tripweather.service;

import com.pjr22.tripweather.model.EmailVerification;
import com.pjr22.tripweather.model.PasswordReset;
import com.pjr22.tripweather.model.User;
import com.pjr22.tripweather.repository.EmailVerificationRepository;
import com.pjr22.tripweather.repository.PasswordResetRepository;
import com.pjr22.tripweather.repository.UserRepository;
import com.pjr22.tripweather.security.TokenUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Phase 2 self-service flows plus Phase 4's forgot- /
 * reset- / change-password and delete-account flows. Mocks all repositories,
 * the email service, the password encoder, and the remember-me revoker so
 * the suite stays fast and doesn't require a Spring context.
 */
@ExtendWith(MockitoExtension.class)
class UserAccountServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private EmailVerificationRepository emailVerificationRepository;
    @Mock private PasswordResetRepository passwordResetRepository;
    @Mock private EmailService emailService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private UserAccountService.RememberMeRevoker rememberMeRevoker;

    /** Token lifetime injected into the service under test. Tests assert
     *  expiry math against this value. */
    private static final long TOKEN_LIFETIME_MINUTES = 5L;

    @Spy
    @InjectMocks
    private UserAccountService service =
            new UserAccountService(null, null, null, null, null, null,
                                   "http://localhost:8090", TOKEN_LIFETIME_MINUTES);

    @BeforeEach
    void rewireFields() {
        // @InjectMocks doesn't work cleanly with the constructor's final args
        // when one is a String literal — set fields directly instead.
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "emailVerificationRepository", emailVerificationRepository);
        ReflectionTestUtils.setField(service, "passwordResetRepository", passwordResetRepository);
        ReflectionTestUtils.setField(service, "emailService", emailService);
        ReflectionTestUtils.setField(service, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(service, "rememberMeRevoker", rememberMeRevoker);
    }

    @Test
    void signup_createsDisabledUserAndSendsVerification() {
        when(userRepository.findByEmailIgnoreCase("alice@example.com"))
                .thenReturn(Optional.empty());
        when(passwordEncoder.encode("a-strong-password"))
                .thenReturn("HASH");
        when(userRepository.save(any(User.class)))
                .thenAnswer(invocation -> {
                    User u = invocation.getArgument(0);
                    if (u.getId() == null) u.setId(UUID.randomUUID());
                    return u;
                });

        UserAccountService.SignupResult result =
                service.signup("Alice@Example.com", "a-strong-password", null);

        assertThat(result).isEqualTo(UserAccountService.SignupResult.CREATED);
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User saved = userCaptor.getValue();
        assertThat(saved.getEmail()).isEqualTo("alice@example.com"); // lowercased
        assertThat(saved.getPasswordHash()).isEqualTo("HASH");
        assertThat(saved.isEnabled()).isFalse();
        assertThat(saved.getName()).isEqualTo("alice"); // local-part fallback

        verify(emailVerificationRepository).save(any(EmailVerification.class));
        verify(emailService).sendVerificationEmail(any(User.class), anyString(),
                eq("http://localhost:8090"), eq(TOKEN_LIFETIME_MINUTES));
    }

    @Test
    void signup_existingUnverifiedEmail_resendsVerificationWithoutTouchingAccount() {
        // Simulates: user signed up earlier but never clicked the verification
        // link. They (or someone else who knows the address) try to sign up
        // again — we resend the verification email but don't change the
        // stored password hash or display name.
        User existing = newUser("alice@example.com", false);
        existing.setPasswordHash("ORIGINAL-HASH");
        existing.setName("AliceOriginal");
        when(userRepository.findByEmailIgnoreCase("alice@example.com"))
                .thenReturn(Optional.of(existing));

        UserAccountService.SignupResult result =
                service.signup("ALICE@example.com", "a-different-password", "Imposter");

        assertThat(result).isEqualTo(UserAccountService.SignupResult.RESENT_VERIFICATION);
        // Critical: don't overwrite their password or display name from a
        // second-party signup attempt.
        assertThat(existing.getPasswordHash()).isEqualTo("ORIGINAL-HASH");
        assertThat(existing.getName()).isEqualTo("AliceOriginal");
        verify(userRepository, never()).save(any(User.class));
        verify(emailVerificationRepository, times(1))
                .consumeOpenForUser(eq(existing.getId()), any(LocalDateTime.class));
        verify(emailVerificationRepository, times(1)).save(any(EmailVerification.class));
        verify(emailService, times(1))
                .sendVerificationEmail(eq(existing), anyString(),
                                       eq("http://localhost:8090"), eq(TOKEN_LIFETIME_MINUTES));
    }

    @Test
    void signup_existingVerifiedEmail_returnsAlreadyVerifiedAndDoesNothing() {
        User existing = newUser("alice@example.com", true);
        existing.setPasswordHash("ORIGINAL-HASH");
        when(userRepository.findByEmailIgnoreCase("alice@example.com"))
                .thenReturn(Optional.of(existing));

        UserAccountService.SignupResult result =
                service.signup("alice@example.com", "a-strong-password", null);

        assertThat(result).isEqualTo(UserAccountService.SignupResult.ALREADY_VERIFIED);
        verify(userRepository, never()).save(any(User.class));
        verify(emailVerificationRepository, never()).save(any(EmailVerification.class));
        verify(emailVerificationRepository, never())
                .consumeOpenForUser(any(UUID.class), any(LocalDateTime.class));
        verify(emailService, never()).sendVerificationEmail(any(), anyString(), anyString(), anyLong());
    }

    @Test
    void signup_shortPassword_throws() {
        assertThatThrownBy(() -> service.signup("alice@example.com", "short", null))
                .isInstanceOf(UserAccountService.InvalidPasswordException.class);
        verify(userRepository, never()).save(any(User.class));
        verify(emailService, never()).sendVerificationEmail(any(), anyString(), anyString(), anyLong());
    }

    @Test
    void signup_invalidEmail_throws() {
        assertThatThrownBy(() -> service.signup("not-an-email", "a-strong-password", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void signup_displayNameFallsBackToLocalPart() {
        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("HASH");
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        service.signup("Bob@Example.com", "a-strong-password", "  ");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("bob");
    }

    @Test
    void verifyEmail_validToken_enablesUserAndConsumesToken() {
        String raw = "raw-token-value";
        User user = newUser("alice@example.com", false);
        EmailVerification ev = new EmailVerification();
        ev.setUser(user);
        ev.setTokenHash(TokenUtil.hash(raw));
        ev.setExpiresAt(LocalDateTime.now().plusHours(1));

        when(emailVerificationRepository.findByTokenHash(TokenUtil.hash(raw)))
                .thenReturn(Optional.of(ev));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        User result = service.verifyEmail(raw);

        assertThat(result.isEnabled()).isTrue();
        assertThat(ev.getConsumedAt()).isNotNull();
        verify(emailVerificationRepository).save(ev);
        verify(userRepository).save(user);
    }

    @Test
    void verifyEmail_expiredToken_throws() {
        String raw = "expired-token";
        EmailVerification ev = new EmailVerification();
        ev.setUser(newUser("alice@example.com", false));
        ev.setTokenHash(TokenUtil.hash(raw));
        ev.setExpiresAt(LocalDateTime.now().minusMinutes(1));

        when(emailVerificationRepository.findByTokenHash(TokenUtil.hash(raw)))
                .thenReturn(Optional.of(ev));

        assertThatThrownBy(() -> service.verifyEmail(raw))
                .isInstanceOf(UserAccountService.InvalidTokenException.class);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void verifyEmail_alreadyConsumedToken_throws() {
        String raw = "consumed-token";
        EmailVerification ev = new EmailVerification();
        ev.setUser(newUser("alice@example.com", true));
        ev.setTokenHash(TokenUtil.hash(raw));
        ev.setExpiresAt(LocalDateTime.now().plusHours(1));
        ev.setConsumedAt(LocalDateTime.now().minusMinutes(5));

        when(emailVerificationRepository.findByTokenHash(TokenUtil.hash(raw)))
                .thenReturn(Optional.of(ev));

        assertThatThrownBy(() -> service.verifyEmail(raw))
                .isInstanceOf(UserAccountService.InvalidTokenException.class);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void verifyEmail_unknownToken_throws() {
        when(emailVerificationRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyEmail("nope"))
                .isInstanceOf(UserAccountService.InvalidTokenException.class);
    }

    @Test
    void resendVerification_unknownEmail_silentlyReturns() {
        when(userRepository.findByEmailIgnoreCase("ghost@example.com"))
                .thenReturn(Optional.empty());

        service.resendVerification("ghost@example.com");

        verify(emailService, never()).sendVerificationEmail(any(), anyString(), anyString(), anyLong());
        verify(emailVerificationRepository, never()).save(any(EmailVerification.class));
    }

    @Test
    void resendVerification_alreadyVerifiedUser_silentlyReturns() {
        User user = newUser("alice@example.com", true);
        when(userRepository.findByEmailIgnoreCase("alice@example.com"))
                .thenReturn(Optional.of(user));

        service.resendVerification("alice@example.com");

        verify(emailService, never()).sendVerificationEmail(any(), anyString(), anyString(), anyLong());
        verify(emailVerificationRepository, never()).save(any(EmailVerification.class));
    }

    @Test
    void resendVerification_pendingUser_invalidatesPriorAndSendsFresh() {
        User user = newUser("alice@example.com", false);
        when(userRepository.findByEmailIgnoreCase("alice@example.com"))
                .thenReturn(Optional.of(user));

        service.resendVerification("alice@example.com");

        verify(emailVerificationRepository, times(1))
                .consumeOpenForUser(eq(user.getId()), any(LocalDateTime.class));
        verify(emailVerificationRepository, times(1)).save(any(EmailVerification.class));
        verify(emailService, times(1))
                .sendVerificationEmail(eq(user), anyString(),
                                       eq("http://localhost:8090"), eq(TOKEN_LIFETIME_MINUTES));
    }

    // -------------------- Phase 4: forgot password --------------------

    @Test
    void forgotPassword_unknownEmail_silentlyReturns() {
        when(userRepository.findByEmailIgnoreCase("ghost@example.com"))
                .thenReturn(Optional.empty());

        service.forgotPassword("ghost@example.com");

        verify(emailService, never()).sendPasswordResetEmail(any(), anyString(), anyString(), anyLong());
        verify(passwordResetRepository, never()).save(any(PasswordReset.class));
    }

    @Test
    void forgotPassword_unverifiedUser_silentlyReturns() {
        // Unverified accounts (or guest accounts with no password hash) are
        // not eligible for reset and must not leak that distinction either.
        User pending = newUser("pending@example.com", false);
        pending.setPasswordHash("HASH");
        when(userRepository.findByEmailIgnoreCase("pending@example.com"))
                .thenReturn(Optional.of(pending));

        service.forgotPassword("pending@example.com");

        verify(emailService, never()).sendPasswordResetEmail(any(), anyString(), anyString(), anyLong());
        verify(passwordResetRepository, never()).save(any(PasswordReset.class));
    }

    @Test
    void forgotPassword_guestAccount_silentlyReturns() {
        // The shared 'guest' user is enabled but has no password hash.
        User guest = newUser("guest@local", true);
        guest.setPasswordHash(null);
        when(userRepository.findByEmailIgnoreCase("guest@local"))
                .thenReturn(Optional.of(guest));

        service.forgotPassword("guest@local");

        verify(emailService, never()).sendPasswordResetEmail(any(), anyString(), anyString(), anyLong());
        verify(passwordResetRepository, never()).save(any(PasswordReset.class));
    }

    @Test
    void forgotPassword_verifiedUser_invalidatesPriorAndSendsFresh() {
        User user = newUser("alice@example.com", true);
        user.setPasswordHash("HASH");
        when(userRepository.findByEmailIgnoreCase("alice@example.com"))
                .thenReturn(Optional.of(user));

        service.forgotPassword("ALICE@example.com"); // case-insensitive

        verify(passwordResetRepository, times(1))
                .consumeOpenForUser(eq(user.getId()), any(LocalDateTime.class));
        verify(passwordResetRepository, times(1)).save(any(PasswordReset.class));
        verify(emailService, times(1))
                .sendPasswordResetEmail(eq(user), anyString(),
                                        eq("http://localhost:8090"), eq(TOKEN_LIFETIME_MINUTES));
    }

    // -------------------- Phase 4: reset password --------------------

    @Test
    void resetPassword_validToken_updatesPasswordRevokesTokensAndConsumes() {
        String raw = "reset-token-value";
        User user = newUser("alice@example.com", true);
        user.setPasswordHash("OLD-HASH");
        PasswordReset reset = new PasswordReset();
        reset.setUser(user);
        reset.setTokenHash(TokenUtil.hash(raw));
        reset.setExpiresAt(LocalDateTime.now().plusHours(1));

        when(passwordResetRepository.findByTokenHash(TokenUtil.hash(raw)))
                .thenReturn(Optional.of(reset));
        when(passwordEncoder.encode("a-brand-new-password")).thenReturn("NEW-HASH");
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        service.resetPassword(raw, "a-brand-new-password");

        assertThat(reset.getConsumedAt()).isNotNull();
        verify(passwordResetRepository).save(reset);
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("NEW-HASH");
        assertThat(userCaptor.getValue().isEnabled()).isTrue();
        verify(passwordResetRepository, times(1))
                .consumeOpenForUser(eq(user.getId()), any(LocalDateTime.class));
        verify(rememberMeRevoker, times(1)).removeAllPersistentTokens("alice@example.com");
    }

    @Test
    void resetPassword_unknownToken_throws() {
        when(passwordResetRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resetPassword("nope", "a-strong-password"))
                .isInstanceOf(UserAccountService.InvalidTokenException.class);
        verify(userRepository, never()).save(any(User.class));
        verify(rememberMeRevoker, never()).removeAllPersistentTokens(anyString());
    }

    @Test
    void resetPassword_expiredToken_throws() {
        String raw = "expired";
        PasswordReset reset = new PasswordReset();
        reset.setUser(newUser("alice@example.com", true));
        reset.setTokenHash(TokenUtil.hash(raw));
        reset.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(passwordResetRepository.findByTokenHash(TokenUtil.hash(raw)))
                .thenReturn(Optional.of(reset));

        assertThatThrownBy(() -> service.resetPassword(raw, "a-strong-password"))
                .isInstanceOf(UserAccountService.InvalidTokenException.class);
        verify(userRepository, never()).save(any(User.class));
        verify(rememberMeRevoker, never()).removeAllPersistentTokens(anyString());
    }

    @Test
    void resetPassword_alreadyConsumedToken_throws() {
        String raw = "used";
        PasswordReset reset = new PasswordReset();
        reset.setUser(newUser("alice@example.com", true));
        reset.setTokenHash(TokenUtil.hash(raw));
        reset.setExpiresAt(LocalDateTime.now().plusHours(1));
        reset.setConsumedAt(LocalDateTime.now().minusMinutes(5));
        when(passwordResetRepository.findByTokenHash(TokenUtil.hash(raw)))
                .thenReturn(Optional.of(reset));

        assertThatThrownBy(() -> service.resetPassword(raw, "a-strong-password"))
                .isInstanceOf(UserAccountService.InvalidTokenException.class);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void resetPassword_shortPassword_throwsBeforeTouchingToken() {
        assertThatThrownBy(() -> service.resetPassword("any-token", "short"))
                .isInstanceOf(UserAccountService.InvalidPasswordException.class);
        verify(passwordResetRepository, never()).findByTokenHash(anyString());
    }

    // -------------------- Phase 4: change password --------------------

    @Test
    void changePassword_validCurrent_updatesHashAndRevokesTokens() {
        User user = newUser("alice@example.com", true);
        user.setPasswordHash("CURRENT-HASH");
        when(userRepository.findByEmailIgnoreCase("alice@example.com"))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.matches("current-password", "CURRENT-HASH")).thenReturn(true);
        when(passwordEncoder.encode("a-brand-new-password")).thenReturn("NEW-HASH");
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        service.changePassword("alice@example.com", "current-password", "a-brand-new-password");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("NEW-HASH");
        verify(rememberMeRevoker, times(1)).removeAllPersistentTokens("alice@example.com");
        verify(passwordResetRepository, times(1))
                .consumeOpenForUser(eq(user.getId()), any(LocalDateTime.class));
    }

    @Test
    void changePassword_wrongCurrent_throwsAndDoesNotMutate() {
        User user = newUser("alice@example.com", true);
        user.setPasswordHash("CURRENT-HASH");
        when(userRepository.findByEmailIgnoreCase("alice@example.com"))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.matches("guess", "CURRENT-HASH")).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword("alice@example.com",
                                                       "guess",
                                                       "a-brand-new-password"))
                .isInstanceOf(UserAccountService.InvalidCredentialsException.class);
        verify(userRepository, never()).save(any(User.class));
        verify(rememberMeRevoker, never()).removeAllPersistentTokens(anyString());
    }

    @Test
    void changePassword_shortNewPassword_throws() {
        assertThatThrownBy(() -> service.changePassword("alice@example.com",
                                                       "current-password",
                                                       "short"))
                .isInstanceOf(UserAccountService.InvalidPasswordException.class);
        verify(userRepository, never()).findByEmailIgnoreCase(anyString());
    }

    // -------------------- Phase 4: delete account --------------------

    @Test
    void deleteAccount_validCurrent_deletesUserAndRevokesTokens() {
        User user = newUser("alice@example.com", true);
        user.setPasswordHash("CURRENT-HASH");
        when(userRepository.findByEmailIgnoreCase("alice@example.com"))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.matches("current-password", "CURRENT-HASH")).thenReturn(true);

        service.deleteAccount("alice@example.com", "current-password");

        verify(rememberMeRevoker, times(1)).removeAllPersistentTokens("alice@example.com");
        verify(userRepository, times(1)).delete(user);
    }

    @Test
    void deleteAccount_wrongCurrent_throwsAndDoesNotDelete() {
        User user = newUser("alice@example.com", true);
        user.setPasswordHash("CURRENT-HASH");
        when(userRepository.findByEmailIgnoreCase("alice@example.com"))
                .thenReturn(Optional.of(user));
        when(passwordEncoder.matches("guess", "CURRENT-HASH")).thenReturn(false);

        assertThatThrownBy(() -> service.deleteAccount("alice@example.com", "guess"))
                .isInstanceOf(UserAccountService.InvalidCredentialsException.class);
        verify(userRepository, never()).delete(any(User.class));
        verify(rememberMeRevoker, never()).removeAllPersistentTokens(anyString());
    }

    private static User newUser(String email, boolean enabled) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setName(email.substring(0, email.indexOf('@')));
        u.setEnabled(enabled);
        return u;
    }
}
