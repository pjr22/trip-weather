package com.pjr22.tripweather.repository;

import com.pjr22.tripweather.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for User entities
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    
    /**
     * Find a user by name
     * @param name The name to search for
     * @return Optional containing the user if found
     */
    Optional<User> findByName(String name);
    
    /**
     * Find a user by ID
     * @param id The UUID to search for
     * @return Optional containing the user if found
     */
    Optional<User> findById(UUID id);
}
