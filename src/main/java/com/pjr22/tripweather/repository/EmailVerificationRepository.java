package com.pjr22.tripweather.repository;

import com.pjr22.tripweather.model.EmailVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailVerificationRepository extends JpaRepository<EmailVerification, UUID> {

    Optional<EmailVerification> findByTokenHash(String tokenHash);

    /**
     * Mark every still-open verification token for a user as consumed. Used by
     * "resend verification" so a previously-issued (but not-yet-clicked) link
     * can't still be replayed after the new one is in flight.
     */
    @Modifying
    @Query("UPDATE EmailVerification ev SET ev.consumedAt = :now "
         + "WHERE ev.user.id = :userId AND ev.consumedAt IS NULL")
    int consumeOpenForUser(@Param("userId") UUID userId, @Param("now") LocalDateTime now);

    /**
     * Bulk-delete verification tokens whose {@code expires_at} has already
     * passed. Used by the periodic cleanup job to keep the table tidy;
     * consumed-but-not-yet-expired rows survive until their natural 24h
     * cutoff, which is fine — they're tiny.
     */
    @Modifying
    @Query("DELETE FROM EmailVerification ev WHERE ev.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
