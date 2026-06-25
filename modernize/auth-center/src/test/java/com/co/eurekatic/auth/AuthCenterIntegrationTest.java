package com.co.eurekatic.auth;

import com.co.eurekatic.common.entity.Role;
import com.co.eurekatic.common.entity.User;
import com.co.eurekatic.common.repository.RoleRepository;
import com.co.eurekatic.common.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.client.MockMvcWebTestClient;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the auth-center module.
 *
 * <p>Boots the full Spring context (replacing the Postgres datasource
 * with an in-memory H2) and exercises the authentication surface via
 * the Spring filter chain — Security, custom JWT filter, the
 * {@code JsonLoginFilter}, controllers and exception handlers all
 * participate as they would in production. The {@code MOCK}
 * {@code webEnvironment} is sufficient because MockMvc routes requests
 * through the same {@link jakarta.servlet.Filter} chain that Tomcat
 * would; no real port is opened.
 *
 * <p>Verified contract:
 * <ol>
 *   <li>Bad credentials return 401.</li>
 *   <li>Good credentials return 200 + a JWT-shaped body.</li>
 *   <li>{@code /getInfoUser} accepts the token and returns the user.</li>
 *   <li>Missing token on a protected endpoint returns 401.</li>
 *   <li>Refresh-cookie issuance, rotation, and logout all match the
 *       documented contract.</li>
 * </ol>
 *
 * <p><b>Boot 4.0 migration note:</b> {@code TestRestTemplate} was
 * removed in Spring Boot 4. The replacement for servlet integration
 * tests is {@link WebTestClient} bound to the
 * {@link WebApplicationContext} via {@link MockMvcWebTestClient}. That
 * connector still drives the real DispatcherServlet + Spring Security
 * filter chain — only the transport differs (in-process instead of
 * loopback HTTP). We capture response bodies and the {@code Set-Cookie}
 * header via {@link FluxExchangeResult#getResponseHeaders()} and
 * {@link FluxExchangeResult#getResponseBodyContent()}.
 *
 * <p>Eureka client is disabled so the test does not require a running
 * registry; we test the auth-center in isolation.
 */
@SpringBootTest(
        classes = AuthCenterApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@TestPropertySource(properties = {
        // Replace Postgres with H2 in PostgreSQL compatibility mode
        "spring.datasource.url=jdbc:h2:mem:auth-center-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        // Keep the JWT secret stable and long enough for HS256
        "sso.jwt.secret=integration-test-secret-which-is-at-least-32-bytes-long-1234567890",
        // Don't try to register with Eureka
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false"
})
class AuthCenterIntegrationTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    UserRepository userRepository;

    @Autowired
    RoleRepository roleRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    ObjectMapper mapper;

    /**
     * Built per-test from the live {@link WebApplicationContext} so it
     * sees the same Spring Security filter chain (incl.
     * {@code JwtAuthenticationFilter} and {@code JsonLoginFilter}) that
     * production requests hit.
     */
    private WebTestClient webTestClient;

    @BeforeEach
    void seed() {
        // .apply(springSecurity()) wires the SecurityFilterChain bean
        // (incl. JsonLoginFilter and JwtAuthenticationFilter) into the
        // underlying MockMvc. Without it, MockMvc skips the security
        // chain and POST /login falls through to a 404 because no
        // @PostMapping handles it — the filter is the handler.
        webTestClient = MockMvcWebTestClient.bindToApplicationContext(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        userRepository.deleteAll();
        roleRepository.deleteAll();

        Role userRole = new Role();
        userRole.setName("USER");
        roleRepository.save(userRole);

        Role adminRole = new Role();
        adminRole.setName("ADMIN");
        roleRepository.save(adminRole);

        User alice = new User();
        alice.setUsername("alice");
        alice.setEmail("alice@example.com");
        alice.setFullName("Alice Example");
        alice.setPassword(passwordEncoder.encode("s3cret"));
        alice.setEnabled(true);
        alice.setActive(true);
        alice.setLdap(false);
        alice.addRole(userRole);
        alice.addRole(adminRole);
        userRepository.save(alice);
    }

    @Test
    void loginWithBadCredentialsReturns401() {
        webTestClient.post().uri("/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"alice\",\"password\":\"wrong\"}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginWithGoodCredentialsReturnsJwt() throws Exception {
        byte[] bodyBytes = webTestClient.post().uri("/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"alice\",\"password\":\"s3cret\"}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK)
                .returnResult(Void.class)
                .getResponseBodyContent();

        JsonNode body = mapper.readTree(bodyBytes);
        assertThat(body.get("token").asText()).isNotBlank();
        assertThat(body.get("refreshToken").asText()).isNotBlank();
        assertThat(body.get("expiresIn").asLong()).isEqualTo(3600L);
    }

    @Test
    void getInfoUserAcceptsIssuedToken() throws Exception {
        String token = loginAndGetToken("alice", "s3cret");

        byte[] bodyBytes = webTestClient.get().uri("/getInfoUser")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK)
                .returnResult(Void.class)
                .getResponseBodyContent();

        JsonNode body = mapper.readTree(bodyBytes);
        assertThat(body.get("username").asText()).isEqualTo("alice");
        assertThat(body.get("email").asText()).isEqualTo("alice@example.com");
    }

    @Test
    void getInfoUserWithoutTokenReturns401() {
        webTestClient.get().uri("/getInfoUser")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void googleLoginReturns501() throws Exception {
        byte[] bodyBytes = webTestClient.post().uri("/googleLogin")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"idToken\":\"x\",\"appName\":\"y\"}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.NOT_IMPLEMENTED)
                .returnResult(Void.class)
                .getResponseBodyContent();

        JsonNode body = mapper.readTree(bodyBytes);
        assertThat(body.get("error").asText()).isEqualTo("google_login_not_configured");
    }

    /* ====================== cookie / refresh / logout ====================== */

    @Test
    void loginSetsRefreshCookieWithCorrectAttributes() {
        FluxExchangeResult<Void> result = webTestClient.post().uri("/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"alice\",\"password\":\"s3cret\"}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK)
                .returnResult(Void.class);

        String setCookie = result.getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotNull();
        // Cookie name + scope + flags are the security guarantees the SPA
        // depends on. If any of these change, update the docs.
        assertThat(setCookie).startsWith("sso_refresh=");
        // Path is "/" so the cookie rides both the legacy /auth/refresh
        // path and the gateway-mounted /api/auth/refresh path; see the
        // matching comment in JsonLoginFilter.REFRESH_COOKIE_PATH.
        assertThat(setCookie).contains("Path=/");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("SameSite=Strict");
        assertThat(setCookie).contains("Max-Age=2592000"); // 30 days
        // The test runs over plain HTTP — Secure must NOT be set,
        // because if it were, the browser would never send the cookie
        // back from a non-HTTPS dev environment.
        assertThat(setCookie).doesNotContain("Secure");
    }

    @Test
    void refreshWithoutCookieReturns401() {
        byte[] bodyBytes = webTestClient.post().uri("/auth/refresh")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED)
                .returnResult(Void.class)
                .getResponseBodyContent();

        assertThat(new String(bodyBytes, StandardCharsets.UTF_8)).contains("no_refresh_cookie");
    }

    @Test
    void refreshWithCookieReturnsNewAccessTokenAndRotatedCookie() throws Exception {
        // First, log in to get a cookie.
        FluxExchangeResult<Void> loginResult = webTestClient.post().uri("/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"username\":\"alice\",\"password\":\"s3cret\"}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK)
                .returnResult(Void.class);
        String initialCookie = loginResult.getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(initialCookie).isNotNull();
        String initialValue = extractCookieValue(initialCookie);
        assertThat(initialValue).isNotBlank();

        // Now call /auth/refresh carrying the cookie.
        FluxExchangeResult<Void> refreshResult = webTestClient.post().uri("/auth/refresh")
                .cookie("sso_refresh", initialValue)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK)
                .returnResult(Void.class);

        JsonNode body = mapper.readTree(refreshResult.getResponseBodyContent());
        assertThat(body.get("token").asText()).isNotBlank();
        assertThat(body.get("expiresIn").asLong()).isEqualTo(3600L);

        // The Set-Cookie header is back with a NEW value (rotation).
        String newCookie = refreshResult.getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(newCookie).startsWith("sso_refresh=");
        String newValue = extractCookieValue(newCookie);
        assertThat(newValue).isNotBlank();
        assertThat(newValue).isNotEqualTo(initialValue);
    }

    @Test
    void logoutSetsCookieToMaxAgeZero() {
        FluxExchangeResult<Void> result = webTestClient.post().uri("/auth/logout")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK)
                .returnResult(Void.class);

        String setCookie = result.getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).startsWith("sso_refresh=");
        assertThat(setCookie).contains("Max-Age=0");
        assertThat(setCookie).contains("Path=/");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("SameSite=Strict");
    }

    /* ====================== helpers ====================== */

    /**
     * Extracts the cookie value from a {@code Set-Cookie} header
     * (everything between {@code =} and {@code ;}). Defensive against
     * the various attribute orderings Spring may produce.
     */
    private static String extractCookieValue(String setCookie) {
        int eq = setCookie.indexOf('=');
        int semi = setCookie.indexOf(';');
        if (semi < 0) semi = setCookie.length();
        return setCookie.substring(eq + 1, semi);
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        byte[] bodyBytes = webTestClient.post().uri("/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(mapper.writeValueAsString(
                        Map.of("username", username, "password", password)))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK)
                .returnResult(Void.class)
                .getResponseBodyContent();
        return mapper.readTree(bodyBytes).get("token").asText();
    }
}
