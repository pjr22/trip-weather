package com.pjr22.tripweather.repository;

import com.pjr22.tripweather.model.FavoriteWaypoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link FavoriteWaypoint}.
 *
 * <p>Every JPQL / derived-query method on this interface is silently filtered
 * by the {@code @SQLRestriction("deleted_at IS NULL")} on
 * {@link FavoriteWaypoint}, so soft-deleted rows are invisible to all of the
 * user-facing read paths. The {@code admin*} methods at the bottom use
 * native SQL specifically to bypass that restriction — they are the only
 * callers that should ever see, mutate, or hard-delete soft-deleted rows.
 * Phase 5 of FAVORITES_AND_ROUTE_MGMT.md (mirrors {@link RouteRepository}).
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

    // ------------------------------------------------------------------------
    // Admin / cleanup paths — native SQL to bypass the entity-level
    // @SQLRestriction("deleted_at IS NULL"). Phase 5 of
    // FAVORITES_AND_ROUTE_MGMT.md.
    // ------------------------------------------------------------------------

    /**
     * Soft-delete a single favorite by id. No-op if the row doesn't exist or
     * is already soft-deleted. Native UPDATE bypasses the entity restriction.
     *
     * @return 1 if a row was newly soft-deleted, 0 otherwise
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE favorite_waypoints SET deleted_at = :now "
                 + "WHERE id = :id AND deleted_at IS NULL",
           nativeQuery = true)
    int adminSoftDelete(@Param("id") UUID id, @Param("now") ZonedDateTime now);

    /**
     * Restore a soft-deleted favorite. Native UPDATE — the entity-level
     * {@code @SQLRestriction} would otherwise hide every soft-deleted row
     * from a JPQL UPDATE's WHERE clause, making restore impossible.
     *
     * @return 1 if a row was restored, 0 otherwise (already active or absent)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE favorite_waypoints SET deleted_at = NULL "
                 + "WHERE id = :id AND deleted_at IS NOT NULL",
           nativeQuery = true)
    int adminRestore(@Param("id") UUID id);

    /**
     * Hard-delete a single favorite by id. Used by the admin "Purge" action
     * on already-soft-deleted rows so an operator can immediately reclaim a
     * row without waiting for the grace-window cron sweep. Bypasses
     * {@code @SQLRestriction} so the admin can purge regardless of the
     * current {@code deleted_at} state.
     *
     * @return 1 if a row was deleted, 0 if it didn't exist
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM favorite_waypoints WHERE id = :id",
           nativeQuery = true)
    int adminHardDelete(@Param("id") UUID id);

    /**
     * Stage 2 of the cleanup cron: hard-delete every soft-deleted favorite
     * whose {@code deleted_at} is older than the grace window. Mirrors
     * {@link RouteRepository#hardDeleteSoftDeletedBefore} so the same
     * env-var ({@code route.cleanup.purge-grace-days}) governs both
     * domains — see {@code GuestRouteCleanupJob.doRouteCleanup}.
     *
     * @return number of rows hard-deleted
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM favorite_waypoints "
                 + "WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff",
           nativeQuery = true)
    int hardDeleteSoftDeletedBefore(@Param("cutoff") ZonedDateTime cutoff);
}
