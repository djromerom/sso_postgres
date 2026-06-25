package com.co.eurekatic.auth.security;

import com.co.eurekatic.common.entity.User;
import com.co.eurekatic.common.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Bridges the {@code common} User entity to Spring Security's
 * {@link UserDetailsService} contract. Returns the {@code User} entity
 * directly because it already implements {@code UserDetails} (see
 * {@code common.entity.User}).
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public AppUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User u = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
        // isEnabled() returns enabled && active. Reject disabled or
        // not-yet-activated accounts here so they cannot log in even if
        // they have a valid password.
        if (!u.isEnabled()) {
            throw new UsernameNotFoundException("User is disabled: " + username);
        }
        return u;
    }
}
