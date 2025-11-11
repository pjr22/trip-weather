package com.pjr22.tripweather.repository;

import com.pjr22.tripweather.model.Route;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
