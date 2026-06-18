package com.pjr22.tripweather.repository;

import com.pjr22.tripweather.model.AiProviderConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link AiProviderConfig}. AI_ASSIST_PLAN.md,
 * Phase 1.
 *
 * <p>Every method here is silently filtered by the
 * {@code @SQLRestriction("deleted_at IS NULL")} on {@link AiProviderConfig}, so
 * soft-deleted rows are invisible to all read paths. Mirrors
 * {@link FavoriteWaypointRepository}.
 */
@Repository
public interface AiProviderConfigRepository extends JpaRepository<AiProviderConfig, UUID> {

    /**
     * A user's active provider configs, alphabetical by nickname
     * (case-insensitive). Backs {@code GET /api/ai/providers}.
     */
    @Query("SELECT c FROM AiProviderConfig c "
         + "WHERE c.user.id = :userId "
         + "ORDER BY LOWER(c.nickname) ASC")
    List<AiProviderConfig> findAllByUser(@Param("userId") UUID userId);

    /**
     * Resolve a config by id and verify ownership in one query. The single-item
     * read + the two write endpoints use this so a wrong-owner id surfaces as
     * 404 rather than 403 — same posture as
     * {@link FavoriteWaypointRepository#findByIdAndUserId}.
     */
    Optional<AiProviderConfig> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Reject duplicate-nickname requests before relying on the partial-unique
     * index. Case-insensitive — the index is on {@code LOWER(nickname)}.
     */
    boolean existsByUserIdAndNicknameIgnoreCase(UUID userId, String nickname);
}
