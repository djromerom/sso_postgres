package com.co.eurekatic.auth.web;

import com.co.eurekatic.common.dto.AuthDtos.UserSummary;
import com.co.eurekatic.common.entity.Role;
import com.co.eurekatic.common.entity.User;
import com.co.eurekatic.common.repository.UserRepository;
import com.co.eurekatic.common.security.AuthPrincipal;
import com.co.eurekatic.common.security.JwtTokenService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * REST endpoints exposed by auth-center. POST /login is NOT in this
 * controller — it's handled by {@link JsonLoginFilter} which sets
 * the response body directly. Everything here is a GET.
 */
@RestController
@RequestMapping
public class AuthController {

    private final UserRepository userRepository;
    private final JwtTokenService jwt;

    public AuthController(UserRepository userRepository, JwtTokenService jwt) {
        this.userRepository = userRepository;
        this.jwt = jwt;
    }

    /**
     * Returns a fresh access token for the bearer of the supplied
     * refresh token. The refresh token is currently a UUID; for the MVP
     * we accept any non-empty string and issue a new access token for
     * the user identified by the {@code Authorization} header. A real
     * implementation would verify the refresh token against a stored
     * value, rotate it, and revoke the old one.
     */
    @GetMapping("/getToken")
    public ResponseEntity<?> getToken(
            @RequestParam("refreshToken") String refreshToken,
            Authentication authentication) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new AccessDeniedException("Missing refreshToken");
        }
        String username = principalName(authentication);
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(username));
        Set<String> roles = roleNames(user);
        return ResponseEntity.ok(new com.co.eurekatic.common.dto.AuthDtos.TokenResponse(
                jwt.issueAccessToken(username, roles),
                refreshToken,
                3_600L));
    }

    /**
     * Service-to-service login. The {@code apiToken} is a long-lived
     * credential bound to a user; on success we issue an access token
     * marked {@code typ=api}.
     */
    @GetMapping("/getApiToken")
    public ResponseEntity<?> getApiToken(@RequestParam("apiToken") String apiToken) {
        User user = userRepository.findByApiToken(apiToken)
                .orElseThrow(() -> new AccessDeniedException("Unknown apiToken"));
        if (!user.isEnabled()) {
            throw new AccessDeniedException("User is disabled");
        }
        Set<String> roles = roleNames(user);
        return ResponseEntity.ok(new com.co.eurekatic.common.dto.AuthDtos.TokenResponse(
                jwt.issueApiToken(user.getUsername(), roles),
                user.getApiToken(),
                86_400L));
    }

    /**
     * Returns the authenticated user's profile. Requires a valid Bearer
     * token (the {@link JwtAuthenticationFilter} populates the
     * SecurityContext from the token).
     */
    @GetMapping("/getInfoUser")
    public ResponseEntity<UserSummary> getInfoUser(Authentication authentication) {
        String username = principalName(authentication);
        User u = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(username));
        return ResponseEntity.ok(toSummary(u));
    }

    /**
     * Lists the active users in the system. Permit-all in the MVP
     * because the legacy endpoint was also unauthenticated; in
     * production this should be gated to {@code ADMIN}.
     */
    @GetMapping("/getUsersSSO")
    public ResponseEntity<List<UserSummary>> getUsersSSO() {
        List<UserSummary> out = userRepository.findAll().stream()
                .filter(User::isEnabled)
                .map(this::toSummary)
                .collect(Collectors.toList());
        return ResponseEntity.ok(out);
    }

    /* ====================== helpers ====================== */

    /**
     * Pulls the username off the {@link Authentication} regardless of
     * whether the principal is a {@link User} entity (login flow) or
     * an {@link AuthPrincipal} record (token-bearer flow).
     */
    private String principalName(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new AccessDeniedException("Missing authentication");
        }
        Object p = authentication.getPrincipal();
        if (p instanceof User u) return u.getUsername();
        if (p instanceof AuthPrincipal ap) return ap.username();
        return authentication.getName();
    }

    private Set<String> roleNames(User u) {
        return u.getRoles().stream()
                .map(Role::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private UserSummary toSummary(User u) {
        return new UserSummary(
                u.getId(),
                u.getUsername(),
                u.getEmail(),
                u.getFullName(),
                u.isEnabled(),
                u.isLdap(),
                roleNames(u));
    }
}
