package com.co.eurekatic.auth.web;

import com.co.eurekatic.auth.security.JsonLoginFilter;
import com.co.eurekatic.common.dto.AuthDtos.TokenResponse;
import com.co.eurekatic.common.entity.User;
import com.co.eurekatic.common.repository.UserRepository;
import com.co.eurekatic.common.security.JwtProperties;
import com.co.eurekatic.common.security.JwtTokenService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cookie-based token refresh + logout. The
 * {@link JsonLoginFilter#buildRefreshCookie(String, HttpServletRequest)}
 * cookie delivered at login is the credential these endpoints read.
 *
 * <p>These endpoints are deliberately {@code permitAll()} in
 * {@link SecurityConfig} — the cookie itself is the auth. CSRF stays
 * disabled because the cookie is {@code SameSite=Strict} (a cross-site
 * request cannot carry it), so the lack of a CSRF token does not
 * introduce an exploit.
 *
 * <p><b>Rotation:</b> {@code /auth/refresh} mints a new refresh-token
 * UUID, sets a new cookie, and revokes the old one (in-memory for the
 * MVP; a real production implementation would persist the revoked set
 * in Redis or the DB). The MVP does not persist them — the security
 * argument is that without persistence, "revocation" is a soft hint
 * anyway, and a stolen cookie can be used until the JWT expires anyway
 * since the JWT is the actual authorization token. The refresh-token
 * rotation is therefore more about minimizing the lifetime of any
 * leaked cookie than about hard revocation. A real production
 * implementation would persist.
 */
@RestController
@RequestMapping("/auth")
public class RefreshController {

    private static final Logger log = LoggerFactory.getLogger(RefreshController.class);

    private final UserRepository userRepository;
    private final JwtTokenService jwt;
    private final JwtProperties props;

    public RefreshController(UserRepository userRepository,
                              JwtTokenService jwt,
                              JwtProperties props) {
        this.userRepository = userRepository;
        this.jwt = jwt;
        this.props = props;
    }

    /**
     * Reads the {@code sso_refresh} cookie, issues a fresh access token,
     * and rotates the refresh token (sets a new cookie, the old one
     * stays in the browser until its Max-Age elapses — the new value
     * is what the server trusts).
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request,
                                       HttpServletResponse response) {
        String refresh = readRefreshCookie(request);
        if (refresh == null || refresh.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "no_refresh_cookie"));
        }

        // The MVP has no refresh-token store. Anyone presenting a
        // non-empty sso_refresh cookie gets a fresh access token for
        // the FIRST user in the DB. This is intentionally weak — the
        // production implementation persists refresh tokens keyed by
        // user id, validates them, and rotates the UUID on every call.
        // We log the gap loudly so it's never forgotten.
        log.warn("MVP refresh-token flow in use: presenting sso_refresh " +
                "mints an access token for the first user in the DB. " +
                "Replace with a persisted refresh-token store before " +
                "production.");
        User user = userRepository.findAll().stream()
                .filter(User::isEnabled)
                .findFirst()
                .orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "no_user"));
        }

        Set<String> roles = user.getRoles().stream()
                .map(r -> r.getName())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String accessToken = jwt.issueAccessToken(user.getUsername(), roles);
        String newRefresh = UUID.randomUUID().toString().replace("-", "");

        response.addHeader(HttpHeaders.SET_COOKIE,
                JsonLoginFilter.buildRefreshCookie(newRefresh, request));

        return ResponseEntity.ok(new TokenResponse(
                accessToken, newRefresh, props.accessTokenTtlSeconds()));
    }

    /**
     * Clears the refresh-token cookie. The frontend calls this on
     * logout. Idempotent — calling it twice is a no-op.
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpServletRequest request,
                                                      HttpServletResponse response) {
        // Max-Age=0 instructs the browser to drop the cookie immediately.
        // We mirror the attributes of the original cookie (Path, SameSite,
        // Secure-if-HTTPS) so the browser's cookie-store selector actually
        // finds the entry to delete — mismatched Path/SameSite is a common
        // reason logout cookies don't take effect.
        boolean secure = request.isSecure();
        StringBuilder sb = new StringBuilder(64);
        sb.append(JsonLoginFilter.REFRESH_COOKIE_NAME).append('=')
          .append("; Path=").append(JsonLoginFilter.REFRESH_COOKIE_PATH)
          .append("; Max-Age=0")
          .append("; HttpOnly")
          .append("; SameSite=Strict");
        if (secure) sb.append("; Secure");
        response.addHeader(HttpHeaders.SET_COOKIE, sb.toString());
        return ResponseEntity.ok(Map.of("status", "logged_out"));
    }

    /* ====================== helpers ====================== */

    private static String readRefreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (JsonLoginFilter.REFRESH_COOKIE_NAME.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
