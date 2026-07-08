package com.co.eurekatic.ssoadmin.service;

import com.co.eurekatic.common.entity.Query;
import com.co.eurekatic.common.entity.Role;
import com.co.eurekatic.common.entity.User;
import com.co.eurekatic.common.repository.QueryRepository;
import com.co.eurekatic.common.repository.UserRepository;
import com.co.eurekatic.ssoadmin.dto.QueryDefinition;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-side service backing the catalog endpoints:
 * <ul>
 *   <li>{@code GET /getQuery?uuid=...} — single-row lookup consumed by
 *       {@code query-service}. Enforces per-row role authorization.</li>
 *   <li>{@code GET /myQueries} — list endpoint consumed by the
 *       admin-ui Queries Catalog page. Filters by per-row role
 *       authorization AND optionally by {@code microserviceId}.</li>
 * </ul>
 *
 * <p>Authorization model: the caller must have at least one role
 * in {@link Query#getRoles()}, OR the query must be
 * {@link Query#isPublicEnd()} {@code = true}, OR the caller must
 * hold the {@code ADMIN} role (super-user bypass). The check is
 * enforced here (not in Spring Security) because the policy is
 * per-row, not per-endpoint.
 *
 * <p>Why {@code EntityGraph} on the repository: the {@code roles}
 * collection is {@code LAZY}, but authorization needs to read it.
 * Without the eager fetch, the {@code @Transactional} boundary ends
 * before the lazy load and we get a
 * {@code LazyInitializationException}. The repository declares every
 * query method with {@code @EntityGraph(attributePaths = "roles")}.
 *
 * <p>Public-endpoint bypass: if {@link Query#isPublicEnd()} is true,
 * the caller is not required to have a bound role — any
 * authenticated caller may resolve it. Captcha is still enforced
 * downstream by {@code query-service} when the
 * {@code QueryDefinition#captcha()} flag is true.
 */
@Service
public class QueryCatalogService {

    private final QueryRepository queryRepo;
    private final UserRepository userRepo;

    public QueryCatalogService(QueryRepository queryRepo,
                               UserRepository userRepo) {
        this.queryRepo = queryRepo;
        this.userRepo = userRepo;
    }

    /**
     * Resolves the query definition for the given uuid, checking
     * that the caller (identified by their email) has at least
     * one role bound to the query (or the query is {@code publicEnd}).
     *
     * @throws AccessDeniedException if the query is unknown OR
     *         the user has no role that grants access. We
     *         deliberately use the SAME exception for both cases
     *         so a probe for the existence of a uuid gets the
     *         same response as a denied access — the catalog is
     *         not a discovery service.
     */
    @Transactional(readOnly = true)
    public QueryDefinition resolve(String uuid, String email) {
        Query q = queryRepo.findByUuid(uuid)
                .orElseThrow(() -> new AccessDeniedException(
                        "No tiene acceso al query: " + uuid));

        if (hasAdminRole(email) || q.isPublicEnd() || userHasAccessTo(q, email)) {
            return QueryDefinition.fromEntity(q);
        }

        // Same exception as the missing case above — the catalog
        // is not a discovery service.
        throw new AccessDeniedException("No tiene acceso al query: " + uuid);
    }

    /**
     * Lists the queries the caller is authorized to see, optionally
     * filtered by {@code microserviceId}. ADMIN callers see every
     * query regardless of role binding (matches the existing admin
     * CRUD semantics); everyone else is filtered by per-row role
     * authorization.
     *
     * <p>The result is id-ordered (stable across page loads) and
     * never includes the bound role ids — those are admin-only and
     * are surfaced via {@code QueryResponse} on the admin CRUD
     * surface.
     *
     * <p>An empty result is a 200 with {@code []}, NOT a 403: the
     * endpoint is asking "what can I see?", and the answer "nothing"
     * is a legitimate response.
     */
    @Transactional(readOnly = true)
    public List<QueryDefinition> listForCaller(String email, Long microserviceId) {
        boolean isAdmin = hasAdminRole(email);
        List<Query> rows = (microserviceId == null)
                ? queryRepo.findAllByOrderByIdAsc()
                : queryRepo.findAllByMicroservice_IdOrderByIdAsc(microserviceId);
        return rows.stream()
                .filter(q -> isAdmin || q.isPublicEnd() || userHasAccessTo(q, email))
                .map(QueryDefinition::fromEntity)
                .toList();
    }

    /* ====================== internals ====================== */

    /**
     * Per-row authorization: the user's roles must intersect the
     * query's bound roles. The email → roles lookup goes
     * through {@code userRepo} (which loads {@code User.roles}
     * EAGER — see {@code User.java}) so no lazy issue here.
     *
     * <p>Reused by {@link #resolve(String, String)} and
     * {@link #listForCaller(String, Long)} so the auth model stays
     * in one place. ADMIN bypass happens upstream of this method —
     * callers check {@link #hasAdminRole(String)} first.
     */
    private boolean userHasAccessTo(Query q, String email) {
        Set<String> userRoles = userRepo.findByEmail(email)
                .map(u -> u.getRoles().stream()
                        .map(Role::getName)
                        .collect(Collectors.toSet()))
                .orElse(Set.of());
        if (userRoles.isEmpty()) {
            return false;
        }
        Set<String> queryRoles = q.getRoles().stream()
                .map(Role::getName)
                .collect(Collectors.toSet());
        return userRoles.stream().anyMatch(queryRoles::contains);
    }

    /**
     * Super-user bypass: a caller with the {@code ADMIN} role sees
     * every query regardless of role binding. This mirrors the
     * existing admin CRUD surface ({@code QueryAdminController}),
     * which is gated by {@code hasRole("ADMIN")} at the URL matcher.
     *
     * <p>If the email does not exist (deleted user, LDAP
     * deprovisioning race) we treat them as non-admin and let the
     * per-row filter apply — a 403 here would be a privilege
     * escalation for a deleted user.
     */
    private boolean hasAdminRole(String email) {
        Optional<User> userOpt = userRepo.findByEmail(email);
        if (userOpt.isEmpty()) return false;
        return userOpt.get().getRoles().stream()
                .anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getName()));
    }
}
