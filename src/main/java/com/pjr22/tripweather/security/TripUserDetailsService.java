package com.pjr22.tripweather.security;

import com.pjr22.tripweather.model.User;
import com.pjr22.tripweather.repository.UserRepository;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class TripUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public TripUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("No user with email: " + email));

        // A user without a password hash (e.g. the shared guest user) cannot log in;
        // surface that as "not found" to keep authentication flows consistent.
        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            throw new UsernameNotFoundException("No user with email: " + email);
        }

        return org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .disabled(!user.isEnabled())
                .authorities(AuthorityUtils.NO_AUTHORITIES)
                .build();
    }
}
