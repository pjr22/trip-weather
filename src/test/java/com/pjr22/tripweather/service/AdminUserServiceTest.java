package com.pjr22.tripweather.service;

import com.pjr22.tripweather.dto.AdminUserDeleteResult;
import com.pjr22.tripweather.model.User;
import com.pjr22.tripweather.repository.EmailVerificationRepository;
import com.pjr22.tripweather.repository.PasswordResetRepository;
import com.pjr22.tripweather.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminUserService}. The list path goes through
 * {@link EntityManager#createNativeQuery} — that's exercised by manual
 * smoke testing against a live PostgreSQL (the SQL has correlated
 * sub-queries that aren't worth faking via Mockito). Here we focus on the
 * mutation paths: enable/disable tri-state, force-verify clearing both
 * verifications AND resets, and the delete count + cascade contract.
 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private EmailVerificationRepository emailVerificationRepository;
    @Mock private PasswordResetRepository passwordResetRepository;
    @Mock private EntityManager entityManager;
    @Mock private Query countRoutesQuery;

    private AdminUserService service;

    @BeforeEach
    void setUp() {
        service = new AdminUserService(
                userRepository, emailVerificationRepository, passwordResetRepository);
        // EntityManager is injected via @PersistenceContext at runtime;
        // for unit tests inject it via reflection.
        ReflectionTestUtils.setField(service, "em", entityManager);
    }

    // ---- setEnabled ---------------------------------------------------------

    @Test
    void setEnabled_returnsUpdated_whenValueChanges() {
        UUID id = UUID.randomUUID();
        User user = newUser(id, false);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        AdminUserService.SetEnabledOutcome outcome = service.setEnabled(id, true);

        assertThat(outcome).isEqualTo(AdminUserService.SetEnabledOutcome.UPDATED);
        assertThat(user.isEnabled()).isTrue();
        verify(userRepository).save(user);
    }

    @Test
    void setEnabled_returnsNoChange_andSkipsSave_whenAlreadyAtTarget() {
        UUID id = UUID.randomUUID();
        User user = newUser(id, true);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        AdminUserService.SetEnabledOutcome outcome = service.setEnabled(id, true);

        assertThat(outcome).isEqualTo(AdminUserService.SetEnabledOutcome.NO_CHANGE);
        verify(userRepository, never()).save(any());
    }

    @Test
    void setEnabled_returnsNotFound_whenUserMissing() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        AdminUserService.SetEnabledOutcome outcome = service.setEnabled(id, true);

        assertThat(outcome).isEqualTo(AdminUserService.SetEnabledOutcome.NOT_FOUND);
        verify(userRepository, never()).save(any());
    }

    @Test
    void setEnabled_canDisableAnEnabledUser() {
        UUID id = UUID.randomUUID();
        User user = newUser(id, true);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        AdminUserService.SetEnabledOutcome outcome = service.setEnabled(id, false);

        assertThat(outcome).isEqualTo(AdminUserService.SetEnabledOutcome.UPDATED);
        assertThat(user.isEnabled()).isFalse();
        verify(userRepository).save(user);
    }

    // ---- forceVerify --------------------------------------------------------

    @Test
    void forceVerify_consumesBothVerificationsAndResets_andEnablesUser() {
        UUID id = UUID.randomUUID();
        User user = newUser(id, false);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        // Both repos report consumed rows.
        when(emailVerificationRepository.consumeOpenForUser(eq(id), any(LocalDateTime.class)))
                .thenReturn(1);
        when(passwordResetRepository.consumeOpenForUser(eq(id), any(LocalDateTime.class)))
                .thenReturn(2);

        boolean result = service.forceVerify(id);

        assertThat(result).isTrue();
        assertThat(user.isEnabled()).isTrue();
        verify(userRepository).save(user);
        ArgumentCaptor<LocalDateTime> verifyNow = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(emailVerificationRepository).consumeOpenForUser(eq(id), verifyNow.capture());
        ArgumentCaptor<LocalDateTime> resetNow = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(passwordResetRepository).consumeOpenForUser(eq(id), resetNow.capture());
        // Both repository calls receive the same "now"; the service computes
        // it once per call so both tables are consistent.
        assertThat(verifyNow.getValue()).isEqualTo(resetNow.getValue());
    }

    @Test
    void forceVerify_clearsResetsEvenWhenNoVerificationsArePending() {
        // The decision in ADMIN_CONSOLE.md Phase 4 is that force-verify ALWAYS
        // clears both tables. Even on a "no pending verification" user, a
        // stale reset is dropped — operator intent is "make this user good".
        UUID id = UUID.randomUUID();
        User user = newUser(id, true);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(emailVerificationRepository.consumeOpenForUser(eq(id), any(LocalDateTime.class)))
                .thenReturn(0);
        when(passwordResetRepository.consumeOpenForUser(eq(id), any(LocalDateTime.class)))
                .thenReturn(1);

        boolean result = service.forceVerify(id);

        assertThat(result).isTrue();
        verify(emailVerificationRepository).consumeOpenForUser(eq(id), any(LocalDateTime.class));
        verify(passwordResetRepository).consumeOpenForUser(eq(id), any(LocalDateTime.class));
        // Already enabled, no save.
        verify(userRepository, never()).save(any());
    }

    @Test
    void forceVerify_returnsFalse_whenUserMissing() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        boolean result = service.forceVerify(id);

        assertThat(result).isFalse();
        verify(emailVerificationRepository, never()).consumeOpenForUser(any(UUID.class), any(LocalDateTime.class));
        verify(passwordResetRepository, never()).consumeOpenForUser(any(UUID.class), any(LocalDateTime.class));
        verify(userRepository, never()).save(any());
    }

    // ---- delete -------------------------------------------------------------

    @Test
    void delete_returnsNull_whenUserMissing() {
        UUID id = UUID.randomUUID();
        when(userRepository.existsById(id)).thenReturn(false);

        AdminUserDeleteResult result = service.delete(id);

        assertThat(result).isNull();
        verify(userRepository, never()).deleteById(any());
    }

    @Test
    void delete_returnsActiveAndSoftDeletedCounts_andDeletesUser() {
        UUID id = UUID.randomUUID();
        when(userRepository.existsById(id)).thenReturn(true);
        // PostgreSQL SUM(CASE ...) returns BigInteger / BigDecimal via JDBC;
        // service casts via Number, so any Number works here.
        Object[] counts = new Object[] { Long.valueOf(3L), Long.valueOf(2L) };
        when(entityManager.createNativeQuery(any(String.class))).thenReturn(countRoutesQuery);
        when(countRoutesQuery.setParameter(eq("userId"), eq(id))).thenReturn(countRoutesQuery);
        when(countRoutesQuery.getSingleResult()).thenReturn(counts);

        AdminUserDeleteResult result = service.delete(id);

        assertThat(result).isNotNull();
        assertThat(result.getActiveRoutesDeleted()).isEqualTo(3L);
        assertThat(result.getSoftDeletedRoutesDeleted()).isEqualTo(2L);
        verify(userRepository).deleteById(id);
    }

    @Test
    void delete_handlesNullCounts_whenUserHasNoRoutes() {
        // PostgreSQL SUM over an empty set returns NULL, not zero. The
        // service must coalesce to 0 so the API stays well-typed.
        UUID id = UUID.randomUUID();
        when(userRepository.existsById(id)).thenReturn(true);
        Object[] counts = new Object[] { null, null };
        when(entityManager.createNativeQuery(any(String.class))).thenReturn(countRoutesQuery);
        when(countRoutesQuery.setParameter(eq("userId"), eq(id))).thenReturn(countRoutesQuery);
        when(countRoutesQuery.getSingleResult()).thenReturn(counts);

        AdminUserDeleteResult result = service.delete(id);

        assertThat(result.getActiveRoutesDeleted()).isZero();
        assertThat(result.getSoftDeletedRoutesDeleted()).isZero();
        verify(userRepository).deleteById(id);
    }

    private static User newUser(UUID id, boolean enabled) {
        User u = new User();
        u.setId(id);
        u.setEmail("user-" + id + "@example.com");
        u.setName("User " + id);
        u.setPasswordHash("$2a$10$irrelevant");
        u.setEnabled(enabled);
        u.setCreated(LocalDateTime.now());
        return u;
    }
}
