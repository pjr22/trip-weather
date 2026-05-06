package com.pjr22.tripweather.security;

import com.pjr22.tripweather.model.User;
import com.pjr22.tripweather.repository.UserRepository;
import com.pjr22.tripweather.service.UserManagementService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Resolves the currently authenticated user from the Spring Security context.
 * Returns {@link Optional#empty()} for anonymous requests; falls back to the
 * shared guest user for save/search flows that must succeed without a login.
 */
@Service
public class CurrentUserService {

    private final UserRepository userRepository;
    private final UserManagementService userManagementService;

    public CurrentUserService(UserRepository userRepository,
                              UserManagementService userManagementService) {
        this.userRepository = userRepository;
        this.userManagementService = userManagementService;
    }

    public Optional<User> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || isAnonymous(auth)) {
            return Optional.empty();
        }
        String email = auth.getName();
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return userRepository.findByEmailIgnoreCase(email);
    }

    public User currentUserOrGuest() {
        return currentUser().orElseGet(userManagementService::getOrCreateGuestUser);
    }

    public boolean isAuthenticated() {
        return currentUser().isPresent();
    }

    private static boolean isAnonymous(Authentication auth) {
        return "anonymousUser".equals(auth.getPrincipal());
    }
}
