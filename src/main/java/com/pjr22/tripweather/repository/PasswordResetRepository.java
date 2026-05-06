package com.pjr22.tripweather.repository;

import com.pjr22.tripweather.model.PasswordReset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PasswordResetRepository extends JpaRepository<PasswordReset, UUID> {

    Optional<PasswordReset> findByTokenHash(String tokenHash);

    /**
     * Mark every still-open password-reset token for a user as consumed. Used
     * by "forgot password" to invalidate any prior link before issuing a new
     * one, and by change-password / reset-password to ensure a freshly-issued
     * link can't be replayed after the password has changed.
     */
    @Modifying
    @Query("UPDATE PasswordReset pr SET pr.consumedAt = :now "
         + "WHERE pr.user.id = :userId AND pr.consumedAt IS NULL")
    int consumeOpenForUser(@Param("userId") UUID userId, @Param("now") LocalDateTime now);

    /**
     * Bulk-delete password-reset tokens whose {@code expires_at} has already
     * passed. Used by the periodic cleanup job. Mirrors the verification-token
     * sweep so both tables stay roughly the same size over time.
     */
    @Modifying
    @Query("DELETE FROM PasswordReset pr WHERE pr.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
