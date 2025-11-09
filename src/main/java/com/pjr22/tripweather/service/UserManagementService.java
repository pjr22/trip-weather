package com.pjr22.tripweather.service;

import com.pjr22.tripweather.model.User;
import com.pjr22.tripweather.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing user operations
 */
@Service
@Transactional
public class UserManagementService {
    
    private static final Logger logger = LoggerFactory.getLogger(UserManagementService.class);
    private static final String GUEST_USER_NAME = "guest";
    
    @Autowired
    private UserRepository userRepository;
    
    /**
     * Create a new user with the given name
     * @param name The name of the user to create
     * @return The created user
     */
    public User createUser(String name) {
        logger.info("Creating new user with name: {}", name);
        
        // Check if user already exists
        Optional<User> existingUser = userRepository.findByName(name);
        if (existingUser.isPresent()) {
            logger.info("User with name '{}' already exists with ID: {}", name, existingUser.get().getId());
            return existingUser.get();
        }
        
        // Create new user
        User user = new User();
        user.setName(name);
        // UUID and timestamp will be set by @PrePersist
        
        User savedUser = userRepository.save(user);
        logger.info("Successfully created user with ID: {} and name: {}", savedUser.getId(), savedUser.getName());
        
        return savedUser;
    }
    
    /**
     * Get or create the guest user
     * @return The guest user
     */
    public User getOrCreateGuestUser() {
        logger.debug("Looking for guest user");
        
        Optional<User> guestUser = userRepository.findByName(GUEST_USER_NAME);
        if (guestUser.isPresent()) {
            logger.debug("Found existing guest user with ID: {}", guestUser.get().getId());
            return guestUser.get();
        }
        
        logger.info("Guest user not found, creating new guest user");
        return createUser(GUEST_USER_NAME);
    }
    
    /**
     * Find user by ID
     * @param userId The UUID of the user
     * @return Optional containing the user if found
     */
    @Transactional(readOnly = true)
    public Optional<User> findUserById(UUID userId) {
        logger.debug("Looking for user with ID: {}", userId);
        return userRepository.findById(userId);
    }
    
    /**
     * Get user by ID or return guest user if not found or userId is null
     * @param userId The UUID of the user (can be null)
     * @return The user (guest user if userId is null or user not found)
     */
    public User getUserByIdOrGuest(UUID userId) {
        if (userId == null) {
            logger.debug("User ID is null, returning guest user");
            return getOrCreateGuestUser();
        }
        
        Optional<User> user = findUserById(userId);
        if (user.isPresent()) {
            logger.debug("Found user with ID: {}", userId);
            return user.get();
        } else {
            logger.warn("User with ID {} not found, returning guest user", userId);
            return getOrCreateGuestUser();
        }
    }
}
