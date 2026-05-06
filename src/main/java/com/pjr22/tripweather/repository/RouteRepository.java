package com.pjr22.tripweather.repository;

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
 * Spring Data JPA repository for Route entities
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
     * Bulk-delete routes belonging to a single user that were created before
     * the given cutoff. Issues a single {@code DELETE FROM routes WHERE ...}
     * statement; dependent waypoints are swept by the {@code ON DELETE CASCADE}
     * on {@code waypoints.route_id} added in Phase 1, so no JPA cascade is
     * needed here.
     *
     * <p>Bypasses Hibernate's first-level cache by design — only call from a
     * transaction that doesn't already have these rows loaded (the cleanup
     * scheduler is the intended caller).
     *
     * @param userId  user whose routes are being swept (typically the guest user)
     * @param cutoff  upper bound on {@code created}; rows older than this go
     * @return number of routes deleted (matches the cleanup-job log line)
     */
    @Modifying
    @Query("DELETE FROM Route r WHERE r.user.id = :userId AND r.created < :cutoff")
    int deleteByUserIdAndCreatedBefore(@Param("userId") UUID userId,
                                       @Param("cutoff") ZonedDateTime cutoff);
}
