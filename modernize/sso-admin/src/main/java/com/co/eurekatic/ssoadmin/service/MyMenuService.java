package com.co.eurekatic.ssoadmin.service;

import com.co.eurekatic.common.entity.App;
import com.co.eurekatic.common.repository.AppRepository;
import com.co.eurekatic.common.repository.RoleRepository;
import com.co.eurekatic.common.repository.RouteRepository;
import com.co.eurekatic.ssoadmin.dto.RouteResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves the per-user sidebar menu from the JWT authorities.
 *
 * <p>The endpoint {@code GET /sso-admin/myMenu} consumes this
 * service; the frontend then renders the sidebar from the
 * returned routes. Today the sidebar in {@code admin-ui} is
 * hardcoded — once the FE is wired to this endpoint, the
 * hardcoded list disappears entirely.
 *
 * <p>Two flavors of authorization are honored (mirrors the
 * {@code /myQueries} pattern: broad + fine-grained both win):
 * <ul>
 *   <li><b>Broad</b> — the JWT carries a role bound to an
 *       {@link com.co.eurekatic.common.entity.App App} via
 *       {@code role_app}; the user sees all routes in that app.</li>
 *   <li><b>Fine-grained</b> — the JWT carries a role bound
 *       directly to the route via {@code role_route}; the user
 *       sees that specific route even if it's outside any app
 *       ("orphan" route).</li>
 * </ul>
 *
 * <p>JWT authorities come back as {@code ROLE_<NAME>} (see
 * {@link com.co.eurekatic.ssoadmin.config.JwtAuthenticationFilter}).
 * We strip the prefix to look up the {@link com.co.eurekatic.common.entity.Role Role}
 * entity by {@code name}, which is the authority stored in
 * {@code auth-center}'s JWT claims.
 *
 * <p>Optional scoping by app name: {@link #forCaller(Authentication, String)}
 * restricts the response to routes whose {@code id_app} points at the
 * resolved {@link App} id. The SPA bakes {@code VITE_APP_NAME}
 * at build time and forwards it via {@code ?app=...}; multi-tenant
 * edits or any "show all my menus" use-case call the un-scoped
 * {@link #forCaller(Authentication)} instead.
 */
@Service
public class MyMenuService {

    private final RouteRepository routeRepo;
    private final RoleRepository roleRepo;
    private final AppRepository appRepo;

    public MyMenuService(RouteRepository routeRepo,
                         RoleRepository roleRepo,
                         AppRepository appRepo) {
        this.routeRepo = routeRepo;
        this.roleRepo = roleRepo;
        this.appRepo = appRepo;
    }

    /**
     * Returns every route visible to the caller across every
     * app the caller has access to. Empty list is a legitimate
     * answer (the user has roles, but none of them grant any
     * menu access — admin should investigate missing
     * {@code role_app} / {@code role_route} bindings).
     */
    @Transactional(readOnly = true)
    public List<RouteResponse> forCaller(Authentication auth) {
        Set<Long> roleIds = roleIdsFromAuth(auth);
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return routeRepo.findVisibleForRoles(roleIds).stream()
                .map(RouteResponse::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Returns the routes visible to the caller that ALSO belong
     * to the named {@link App}. Used by the SPA when it has a
     * {@code VITE_APP_NAME} baked at build time — the SPA then
     * sees only the menu of its own app, never the menus of
     * other apps the same user might be entitled to.
     *
     * <p>Short-circuits (return {@code List.of()}, never throw):
     * <ul>
     *   <li>{@code appName} is null — caller didn't scope
     *       (clients that haven't migrated yet; preserves
     *       backwards-compat semantics — except we don't fall
     *       through to the un-scoped query here, callers wanting
     *       the union call the 1-arg overload).</li>
     *   <li>{@code appName} is blank after trim — same as null.</li>
     *   <li>No {@link App} row with that exact name (case-sensitive;
     *       {@code app.name} is {@code UNIQUE NOT NULL} per
     *       {@link com.co.eurekatic.common.entity.App}).</li>
     *   <li>Caller's role set is empty (not logged in with
     *       any recognized authority).</li>
     * </ul>
     * All four collapse to {@code 200 + []} on the wire, which
     * is consistent with {@link #forCaller(Authentication)}'s
     * "empty list is a legitimate answer" contract — the
     * endpoint answers "what can I see?", and "nothing" is
     * always a 200.
     */
    @Transactional(readOnly = true)
    public List<RouteResponse> forCaller(Authentication auth, String appName) {
        Set<Long> roleIds = roleIdsFromAuth(auth);
        if (roleIds.isEmpty()) {
            return List.of();
        }
        String trimmed = appName == null ? null : appName.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            return List.of();
        }
        Long appId = appRepo.findByName(trimmed).map(app -> app.getId()).orElse(null);
        if (appId == null) {
            return List.of();
        }
        return routeRepo.findVisibleForRoles(roleIds, appId).stream()
                .map(RouteResponse::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Maps {@code ROLE_<NAME>} authorities to role ids by
     * name. The lookup is O(roles × roles_table); admin-ui
     * users typically have 2–5 roles, so the constant is
     * tiny and the in-memory {@code findAll} is fine. If
     * role counts ever explode, swap this for a batch
     * {@code findByNameIn(...)} on {@link RoleRepository}.
     */
    private Set<Long> roleIdsFromAuth(Authentication auth) {
        if (auth == null) return Set.of();
        Set<String> names = new LinkedHashSet<>();
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if (a != null && a.startsWith("ROLE_")) {
                names.add(a.substring("ROLE_".length()));
            }
        }
        if (names.isEmpty()) return Set.of();
        Set<Long> ids = new LinkedHashSet<>();
        roleRepo.findAll().forEach(r -> {
            if (names.contains(r.getName())) {
                ids.add(r.getId());
            }
        });
        return ids;
    }
}