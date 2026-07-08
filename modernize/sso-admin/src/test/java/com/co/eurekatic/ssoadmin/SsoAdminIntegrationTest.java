package com.co.eurekatic.ssoadmin;

import com.co.eurekatic.common.entity.Role;
import com.co.eurekatic.common.entity.User;
import com.co.eurekatic.common.repository.EndpointRepository;
import com.co.eurekatic.common.repository.GroupRepository;
import com.co.eurekatic.common.repository.MicroserviceRepository;
import com.co.eurekatic.common.repository.RoleRepository;
import com.co.eurekatic.common.repository.RouteRepository;
import com.co.eurekatic.common.repository.UserRepository;
import com.co.eurekatic.common.security.JwtTokenService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.client.MockMvcWebTestClient;
import org.springframework.web.context.WebApplicationContext;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;

/**
 * End-to-end integration test for the sso-admin module.
 *
 * <p>Boots the full Spring context with an in-memory H2
 * database (PostgreSQL compatibility mode + NON_KEYWORDS=GROUPS
 * so the {@code groups} table can be created) and exercises the
 * HTTP surface through the real Spring Security filter chain
 * (incl. {@code JwtAuthenticationFilter}), asserting the security
 * rules, the activation flow, and the role binding round-trip.
 *
 * <p>The {@link com.co.eurekatic.ssoadmin.service.EmailService}
 * bean is replaced with a mock — we don't want SMTP in tests.
 *
 * <p><b>Boot 4.0 migration note:</b> {@code TestRestTemplate} and
 * {@code @MockBean} were both removed in Spring Boot 4. HTTP calls
 * now go through {@link WebTestClient} bound to the
 * {@link WebApplicationContext} via {@code MockMvcWebTestClient}
 * (same pattern as {@code AuthCenterIntegrationTest}), and the
 * email mock uses {@code @MockitoBean} (the Spring Framework 7
 * replacement for {@code @MockBean}).
 */
@SpringBootTest(
        classes = SsoAdminApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:sso-admin-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=GROUPS;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "sso.jwt.secret=integration-test-secret-which-is-at-least-32-bytes-long-1234567890",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.mail.host=localhost",
        "spring.mail.port=2525",
        // Don't run mail health probe — the fake SMTP isn't
        // actually serving, and the indicator would push the
        // /actuator/health aggregate to 503.
        "management.health.mail.enabled=false"
})
class SsoAdminIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired GroupRepository groupRepository;
    @Autowired MicroserviceRepository microserviceRepository;
    @Autowired EndpointRepository endpointRepository;
    @Autowired RouteRepository routeRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtTokenService jwtService;
    @Autowired ObjectMapper mapper;

    // Don't hit SMTP in tests.
    @MockitoBean
    com.co.eurekatic.ssoadmin.service.EmailService emailServiceMock;

    /**
     * Built per-test from the live {@link WebApplicationContext} so it
     * sees the same Spring Security filter chain (incl.
     * {@code JwtAuthenticationFilter}) that production requests hit.
     */
    private WebTestClient webTestClient;

    @BeforeEach
    void seed() {
        webTestClient = MockMvcWebTestClient.bindToApplicationContext(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        // Order matters: child tables before parents so FK
        // constraints don't trip on the H2 re-init.
        routeRepository.deleteAll();
        endpointRepository.deleteAll();
        microserviceRepository.deleteAll();
        userRepository.deleteAll();
        roleRepository.deleteAll();
        groupRepository.deleteAll();

        Role admin = new Role("ADMIN", "Administrator");
        roleRepository.save(admin);

        User root = new User();
        root.setUsername("root");
        root.setEmail("root@example.com");
        root.setFullName("Root Admin");
        root.setPassword(passwordEncoder.encode("r00t"));
        root.setEnabled(true);
        root.setActive(true);
        root.addRole(admin);
        userRepository.save(root);

        // Default no-op so the mock doesn't blow up.
        doNothing().when(emailServiceMock).sendActivationEmail(any(), any());
        doNothing().when(emailServiceMock).sendRestorePasswordEmail(any(), any());
    }

    /* ====================== public endpoints ====================== */

    @Test
    void healthEndpointIsPublic() {
        byte[] body = webTestClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK)
                .returnResult(Void.class)
                .getResponseBodyContent();

        assertThat(new String(body)).contains("\"status\":\"UP\"");
    }

    @Test
    void protectedEndpointRejectsRequestWithoutToken() {
        webTestClient.get().uri("/getUsers")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
    }

    /* ====================== auth-gated CRUD ====================== */

    @Test
    void getUsersReturnsSeededAdmin() throws Exception {
        String token = tokenFor("root", "ADMIN");

        JsonNode body = getWithAuth("/getUsers", token, HttpStatus.OK);
        assertThat(body.isArray()).isTrue();
        assertThat(body).hasSize(1);
        assertThat(body.get(0).get("username").asText()).isEqualTo("root");
        // The password is NEVER exposed in the response.
        assertThat(body.get(0).has("password")).isFalse();
    }

    @Test
    void createAccountPersistsUserWithDisabledFlagAndSendsEmail() throws Exception {
        String token = tokenFor("root", "ADMIN");
        String body = mapper.writeValueAsString(Map.of(
                "fullName", "Alice Example",
                "username", "alice",
                "email", "alice@example.com",
                "password", "s3cret",
                "passwordConfirm", "s3cret",
                "roleNames", List.of("ADMIN")
        ));

        JsonNode json = postWithAuth("/createAccount", token, body, HttpStatus.CREATED);
        assertThat(json.get("username").asText()).isEqualTo("alice");
        assertThat(json.get("active").asBoolean()).isTrue();
        assertThat(json.get("ldap").asBoolean()).isFalse();

        User stored = userRepository.findByUsername("alice").orElseThrow();
        // Until the activation link is clicked, the user cannot log in.
        assertThat(stored.isEnabled()).isFalse();
        assertThat(stored.getTokenActivation()).isNotBlank();
        // Password was BCrypted, not stored as plaintext.
        assertThat(stored.getPassword()).isNotEqualTo("s3cret");
        assertThat(passwordEncoder.matches("s3cret", stored.getPassword())).isTrue();

        org.mockito.Mockito.verify(emailServiceMock)
                .sendActivationEmail(any(User.class), any());
    }

    @Test
    void createAccountRejectsDuplicateUsernameWith409() throws Exception {
        String token = tokenFor("root", "ADMIN");
        String body = mapper.writeValueAsString(Map.of(
                "fullName", "Another Root",
                "username", "root",
                "email", "other@example.com",
                "password", "s3cret"
        ));

        JsonNode json = postWithAuth("/createAccount", token, body, HttpStatus.CONFLICT);
        assertThat(json.get("code").asText()).isEqualTo("USER_DUPLICATE");
    }

    @Test
    void createAccountRejectsInvalidEmailWith422() throws Exception {
        String token = tokenFor("root", "ADMIN");
        String body = mapper.writeValueAsString(Map.of(
                "fullName", "Bad Email",
                "username", "badmail",
                "email", "not-an-email",
                "password", "s3cret"
        ));

        // @Email validation kicks in before our second-line regex,
        // so the response is the generic VALIDATION_FAILED.
        //
        // Boot 4 / Spring Framework 7 note: the response comes back
        // over the wire as numeric 422 and WebTestClient rehydrates
        // it via HttpStatus.valueOf(422), which resolves to the
        // canonical UNPROCESSABLE_CONTENT constant (RFC 9110 renamed
        // 422's reason phrase). UNPROCESSABLE_ENTITY is kept only as
        // a distinct legacy enum instance for source compatibility —
        // comparing against it here would fail despite both being
        // "422".
        postWithAuth("/createAccount", token, body, HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void activateAccountEnablesUserWithoutAuth() throws Exception {
        // First, create a user — capture the activation token via the
        // mock EmailService argument.
        String token = tokenFor("root", "ADMIN");
        String body = mapper.writeValueAsString(Map.of(
                "fullName", "Bob", "username", "bob", "email", "bob@example.com",
                "password", "s3cret"));
        postWithAuth("/createAccount", token, body, HttpStatus.CREATED);

        User created = userRepository.findByUsername("bob").orElseThrow();
        String activationToken = created.getTokenActivation();
        assertThat(activationToken).isNotBlank();

        // No Authorization header — the link from the email does NOT include one.
        webTestClient.get()
                .uri("/activateAccount?token=" + activationToken + "&password=newpass1")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.OK);

        User after = userRepository.findByUsername("bob").orElseThrow();
        assertThat(after.isEnabled()).isTrue();
        assertThat(after.isActive()).isTrue();
        // Token column cleared on use — cannot be replayed.
        assertThat(after.getTokenActivation()).isNull();
        assertThat(passwordEncoder.matches("newpass1", after.getPassword())).isTrue();
    }

    @Test
    void activateAccountWithUnknownTokenReturns404() {
        webTestClient.get()
                .uri("/activateAccount?token=does-not-exist&password=newpass1")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void roleCreateAndListRoundTrip() throws Exception {
        String token = tokenFor("root", "ADMIN");

        postWithAuth("/role/createRole", token,
                mapper.writeValueAsString(Map.of("name", "AUDITOR", "description", "Read only")),
                HttpStatus.CREATED);

        JsonNode all = getWithAuth("/role/getRoles", token, HttpStatus.OK);
        assertThat(all).hasSizeGreaterThanOrEqualTo(2); // ADMIN + AUDITOR

        JsonNode own = getWithAuth("/role/getRolesOwn", token, HttpStatus.OK);
        // Just make sure the endpoint shape is what the legacy UI expects.
        assertThat(own.isArray()).isTrue();
    }

    @Test
    void groupCreateAndList() throws Exception {
        String token = tokenFor("root", "ADMIN");

        JsonNode created = postWithAuth("/group", token,
                mapper.writeValueAsString(Map.of("name", "Ops", "description", "Operations")),
                HttpStatus.CREATED);
        assertThat(created.get("name").asText()).isEqualTo("Ops");

        JsonNode all = getWithAuth("/group", token, HttpStatus.OK);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).get("memberCount").asInt()).isZero();
    }

    /* ====================== Phase 2: microservice / endpoint / route ====================== */

    @Test
    void microserviceCreateAndListRoundTrip() throws Exception {
        String token = tokenFor("root", "ADMIN");
        String body = mapper.writeValueAsString(Map.of(
                "serviceId", "users-svc",
                "description", "Users microservice",
                "requestUri", "/users/**",
                "targetUriPath", "/api/v1",
                "targetUrlHost", "users.internal",
                "targetUrlPort", "8080"
        ));

        JsonNode createdJson = postWithAuth("/microservice/save", token, body, HttpStatus.CREATED);
        assertThat(createdJson.get("serviceId").asText()).isEqualTo("users-svc");

        JsonNode all = getWithAuth("/microservice/getMicroservices", token, HttpStatus.OK);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).get("serviceId").asText()).isEqualTo("users-svc");
        assertThat(all.get(0).has("password")).isFalse();
    }

    @Test
    void microserviceDuplicateServiceIdReturns409() throws Exception {
        String token = tokenFor("root", "ADMIN");
        String body = mapper.writeValueAsString(Map.of("serviceId", "dup"));

        postWithAuth("/microservice/save", token, body, HttpStatus.CREATED);

        JsonNode second = postWithAuth("/microservice/save", token, body, HttpStatus.CONFLICT);
        assertThat(second.get("code").asText()).isEqualTo("DUPLICATE");
    }

    @Test
    void endpointCreateBindMicroserviceAndCheckEndpoint() throws Exception {
        String token = tokenFor("root", "ADMIN");

        // Create a microservice to bind to.
        JsonNode ms = postWithAuth("/microservice/save", token,
                mapper.writeValueAsString(Map.of("serviceId", "users-svc")),
                HttpStatus.CREATED);
        long microserviceId = ms.get("id").asLong();

        // Create the endpoint.
        JsonNode ep = postWithAuth("/endpoint/save", token,
                mapper.writeValueAsString(Map.of(
                        "method", "GET",
                        "path", "/api/users",
                        "description", "List users",
                        "numberParams", 0)),
                HttpStatus.CREATED);
        long endpointId = ep.get("id").asLong();

        // Bind the microservice.
        webTestClient.post()
                .uri("/endpoint/" + endpointId + "/microservice/" + microserviceId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.NO_CONTENT);

        // Verified by the checked listing.
        JsonNode rows = getWithAuth("/endpoint/" + endpointId + "/microservices/checked", token, HttpStatus.OK);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("serviceId").asText()).isEqualTo("users-svc");
        assertThat(rows.get(0).get("checked").asBoolean()).isTrue();
    }

    @Test
    void endpointDuplicatePathMethodDescriptionReturns409() throws Exception {
        String token = tokenFor("root", "ADMIN");
        String body = mapper.writeValueAsString(Map.of(
                "method", "GET", "path", "/dup", "description", "D", "numberParams", 0));

        postWithAuth("/endpoint/save", token, body, HttpStatus.CREATED);

        postWithAuth("/endpoint/save", token, body, HttpStatus.CONFLICT);
    }

    @Test
    void endpointBindRoleAndCheckedListing() throws Exception {
        String token = tokenFor("root", "ADMIN");

        JsonNode ep = postWithAuth("/endpoint/save", token,
                mapper.writeValueAsString(Map.of(
                        "method", "POST", "path", "/x", "description", "x", "numberParams", 0)),
                HttpStatus.CREATED);
        long endpointId = ep.get("id").asLong();

        long adminRoleId = roleRepository.findByName("ADMIN").orElseThrow().getId();

        webTestClient.post()
                .uri("/endpoint/" + endpointId + "/role/" + adminRoleId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.NO_CONTENT);

        JsonNode rows = getWithAuth("/endpoint/" + endpointId + "/roles/checked", token, HttpStatus.OK);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("name").asText()).isEqualTo("ADMIN");
        assertThat(rows.get(0).get("checked").asBoolean()).isTrue();
    }

    @Test
    void routeCreateWithLegacyZeroParentNormalizesToNull() throws Exception {
        String token = tokenFor("root", "ADMIN");
        String body = mapper.writeValueAsString(Map.of(
                "name", "Home",
                "path", "/home",
                "menuOrder", 1,
                "type", "MENU",
                "idParent", 0    // legacy "root" sentinel
        ));

        JsonNode json = postWithAuth("/route/save", token, body, HttpStatus.CREATED);
        // The response and the persisted row should both have idParent = null.
        assertThat(json.get("idParent").isNull()).isTrue();

        // /route/getRoutesByParent without an idParent should return roots only.
        JsonNode roots = getWithAuth("/route/getRoutesByParent", token, HttpStatus.OK);
        assertThat(roots).hasSize(1);
        assertThat(roots.get(0).get("name").asText()).isEqualTo("Home");
    }

    @Test
    void routeBindRoleAndCheckedListing() throws Exception {
        String token = tokenFor("root", "ADMIN");

        JsonNode created = postWithAuth("/route/save", token,
                mapper.writeValueAsString(Map.of(
                        "name", "Settings", "path", "/settings", "menuOrder", 2)),
                HttpStatus.CREATED);
        long routeId = created.get("id").asLong();

        long adminRoleId = roleRepository.findByName("ADMIN").orElseThrow().getId();
        webTestClient.post()
                .uri("/route/" + routeId + "/role/" + adminRoleId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.NO_CONTENT);

        JsonNode rows = getWithAuth("/route/" + routeId + "/roles/checked", token, HttpStatus.OK);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("name").asText()).isEqualTo("ADMIN");
        assertThat(rows.get(0).get("checked").asBoolean()).isTrue();
    }

    /* ====================== helpers ====================== */

    private String tokenFor(String username, String... roles) {
        Set<String> roleSet = new LinkedHashSet<>(List.of(roles));
        return jwtService.issueAccessToken(username, roleSet);
    }

    private JsonNode getWithAuth(String path, String token, HttpStatus expectedStatus) throws Exception {
        byte[] body = webTestClient.get().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isEqualTo(expectedStatus)
                .returnResult(Void.class)
                .getResponseBodyContent();
        return mapper.readTree(body);
    }

    private JsonNode postWithAuth(String path, String token, String requestBody, HttpStatus expectedStatus) throws Exception {
        byte[] body = webTestClient.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isEqualTo(expectedStatus)
                .returnResult(Void.class)
                .getResponseBodyContent();
        return mapper.readTree(body);
    }
}
