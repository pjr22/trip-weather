package com.pjr22.tripweather.repository;

import com.pjr22.tripweather.model.FavoriteWaypoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link FavoriteWaypoint}.
 *
 * <p>Every method on this interface is silently filtered by the
 * {@code @SQLRestriction("deleted_at IS NULL")} on {@link FavoriteWaypoint},
 * so soft-deleted rows are invisible to the user-facing read paths. Phase 5
 * (admin console) will add {@code admin*} methods using native SQL to bypass
 * the restriction — mirroring {@link RouteRepository}.
 */
@Repository
public interface FavoriteWaypointRepository extends JpaRepository<FavoriteWaypoint, UUID> {

    /**
     * Default listing: a user's active favorites, alphabetical by label
     * (case-insensitive). Backs {@code GET /api/favorites} with no query.
     */
    @Query("SELECT f FROM FavoriteWaypoint f "
         + "WHERE f.user.id = :userId "
         + "ORDER BY LOWER(f.label) ASC")
    List<FavoriteWaypoint> findAllByUser(@Param("userId") UUID userId);

    /**
     * Filtered listing: substring match (case-insensitive) on label OR
     * locationName, scoped to a single user. Backs {@code GET /api/favorites
     * ?search=...}. The {@code :userId AND (...)} grouping is explicit
     * because a Spring Data derived-name equivalent would parse the OR
     * outside the userId scope (matching favorites across all users).
     */
    @Query("SELECT f FROM FavoriteWaypoint f "
         + "WHERE f.user.id = :userId "
         + "  AND (LOWER(f.label)        LIKE LOWER(CONCAT('%', :q, '%')) "
         + "    OR LOWER(f.locationName) LIKE LOWER(CONCAT('%', :q, '%'))) "
         + "ORDER BY LOWER(f.label) ASC")
    List<FavoriteWaypoint> searchByUser(@Param("userId") UUID userId,
                                        @Param("q") String searchText);

    /**
     * Exact-match existence check used by {@code GET /api/favorites/check}
     * (heart-toggle initial state on a fresh map-click). Returns 0 or 1 rows;
     * if more than one favorite at the same (lat,lon,locationName) exists
     * with different labels, the caller takes the first.
     */
    Optional<FavoriteWaypoint> findFirstByUserIdAndLatitudeAndLongitudeAndLocationName(
            UUID userId, Double latitude, Double longitude, String locationName);

    /**
     * Resolve a favorite by id and verify ownership in one query. The two
     * write endpoints ({@code PUT}, {@code DELETE}) use this so a wrong-owner
     * id surfaces as 404 rather than 403 — same posture as
     * {@link RouteRepository#findByIdAndUserId}.
     */
    Optional<FavoriteWaypoint> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Used by the service to reject duplicate-label requests before
     * relying on the partial-unique-index DataIntegrityViolation. Cheaper
     * to surface a 409 from a deliberate check than to translate the
     * SQL state. Case-insensitive — the index is on {@code LOWER(label)}.
     */
    boolean existsByUserIdAndLabelIgnoreCase(UUID userId, String label);
}
