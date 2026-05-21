package com.pjr22.tripweather.repository;

import com.pjr22.tripweather.dto.RouteSummaryDto;
import com.pjr22.tripweather.model.Route;
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
 * Spring Data JPA repository for Route entities.
 *
 * <p>Every JPQL / derived-query method on this interface is silently filtered
 * by the {@code @SQLRestriction("deleted_at IS NULL")} on {@link Route}, so
 * soft-deleted rows are invisible to all of the user-facing read paths. The
 * {@code admin*} methods at the bottom use native SQL specifically to bypass
 * that restriction — they are the only callers that should ever see, mutate,
 * or hard-delete soft-deleted rows. Phase 1 of ADMIN_CONSOLE.md.
 */
@Repository
public interface RouteRepository extends JpaRepository<Route, UUID> {

    /**
     * Find routes by user ID
     * @param userId The user ID to search for
     * @return List of routes belonging to the user
     */
    List<Route> findByUserId(UUID userId);

    /**
     * Find routes by user ID and name (case-insensitive)
     * @param userId The user ID to search for
     * @param name The route name to search for
     * @return List of routes matching the criteria
     */
    List<Route> findByUserIdAndNameIgnoreCase(UUID userId, String name);

    /**
     * Find a route by ID and user ID
     * @param id The route ID to search for
     * @param userId The user ID to search for
     * @return Optional containing the route if found
     */
    Optional<Route> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Find routes by user ID and name containing the search text (case-insensitive)
     * @param userId The user ID to search for
     * @param searchText The text to search for in route names
     * @return List of routes matching the criteria
     */
    List<Route> findByUserIdAndNameContainingIgnoreCase(UUID userId, String searchText);

    /**
     * List a user's routes as {@link RouteSummaryDto} summaries — id, name,
     * created, and pre-counted waypoints — sorted by {@code created DESC}.
     * Phase 4 of FAVORITES_AND_ROUTE_MGMT.md.
     *
     * <p>Uses a JPQL constructor expression so we don't hydrate every
     * route's waypoint collection just to render a list of names. LEFT JOIN
     * + GROUP BY keeps routes with zero waypoints in the result.
     */
    @Query("SELECT new com.pjr22.tripweather.dto.RouteSummaryDto("
         + "    r.id, r.name, r.created, COUNT(w)) "
         + "FROM Route r LEFT JOIN r.waypoints w "
         + "WHERE r.user.id = :userId "
         + "GROUP BY r.id, r.name, r.created "
         + "ORDER BY r.created DESC")
    List<RouteSummaryDto> findSummariesByUser(@Param("userId") UUID userId);

    /**
     * Same as {@link #findSummariesByUser} but filtered by a case-insensitive
     * name substring. Backs {@code GET /api/routes?search=...}.
     */
    @Query("SELECT new com.pjr22.tripweather.dto.RouteSummaryDto("
         + "    r.id, r.name, r.created, COUNT(w)) "
         + "FROM Route r LEFT JOIN r.waypoints w "
         + "WHERE r.user.id = :userId "
         + "  AND LOWER(r.name) LIKE LOWER(CONCAT('%', :q, '%')) "
         + "GROUP BY r.id, r.name, r.created "
         + "ORDER BY r.created DESC")
    List<RouteSummaryDto> searchSummariesByUser(@Param("userId") UUID userId,
                                                @Param("q") String searchText);

    // ------------------------------------------------------------------------
    // Admin / cleanup paths — native SQL to bypass the entity-level
    // @SQLRestriction("deleted_at IS NULL"). Phase 1 of ADMIN_CONSOLE.md.
    // ------------------------------------------------------------------------

    /**
     * Stage 1 of the two-stage cleanup: mark guest-owned routes older than the
     * retention window as soft-deleted. Native UPDATE; the entity restriction
     * does not apply, so this can target rows whose {@code deleted_at} is
     * already null (the only ones that should change).
     *
     * @param userId guest user whose routes are being swept
     * @param cutoff upper bound on {@code created}; rows older than this go
     * @param now    timestamp to write into {@code deleted_at}
     * @return number of rows marked deleted
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE routes SET deleted_at = :now "
                 + "WHERE user_id = :userId AND created < :cutoff AND deleted_at IS NULL",
           nativeQuery = true)
    int softDeleteGuestRoutesCreatedBefore(@Param("userId") UUID userId,
                                           @Param("cutoff") ZonedDateTime cutoff,
                                           @Param("now") ZonedDateTime now);

    /**
     * Stage 2 of the two-stage cleanup: hard-delete any soft-deleted route
     * (regardless of owner) whose {@code deleted_at} is older than the grace
     * window. Native DELETE — the {@code waypoints.route_id ON DELETE CASCADE}
     * sweeps dependent waypoints in the same statement.
     *
     * @param cutoff upper bound on {@code deleted_at}; rows marked before this go
     * @return number of rows hard-deleted
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM routes WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff",
           nativeQuery = true)
    int hardDeleteSoftDeletedBefore(@Param("cutoff") ZonedDateTime cutoff);

    /**
     * Soft-delete a single route by id. No-op if the row doesn't exist or is
     * already soft-deleted. Native UPDATE bypasses the entity restriction
     * (which would otherwise filter out a row already marked deleted, but
     * also — defensively — could surprise a future maintainer who expected
     * UPDATE statements to behave like SELECTs do here).
     *
     * @return 1 if a row was newly soft-deleted, 0 otherwise
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE routes SET deleted_at = :now "
                 + "WHERE id = :id AND deleted_at IS NULL",
           nativeQuery = true)
    int adminSoftDelete(@Param("id") UUID id, @Param("now") ZonedDateTime now);

    /**
     * Restore a soft-deleted route. Native UPDATE — the entity-level
     * {@code @SQLRestriction} would otherwise hide every soft-deleted row
     * from a JPQL UPDATE's WHERE clause, making restore impossible.
     *
     * @return 1 if a row was restored, 0 otherwise (already active or absent)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE routes SET deleted_at = NULL "
                 + "WHERE id = :id AND deleted_at IS NOT NULL",
           nativeQuery = true)
    int adminRestore(@Param("id") UUID id);
}
