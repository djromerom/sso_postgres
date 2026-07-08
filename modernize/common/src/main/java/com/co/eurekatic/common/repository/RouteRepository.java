package com.co.eurekatic.common.repository;

import com.co.eurekatic.common.entity.Route;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * Spring Data JPA repository for {@link Route}.
 *
 * <p>The legacy supports a tree view via
 * {@code getRoutesByParent(parentId)} with {@code 0} meaning
 * "root"; in the JPA port we keep two derived methods and let
 * the service layer decide which one to call.
 */
@Repository
public interface RouteRepository extends JpaRepository<Route, Long> {

    /** All root routes (parent id is null in the modernized schema). */
    List<Route> findByIdParentIsNull();

    /** Direct children of a given parent route. */
    List<Route> findByIdParent(Long idParent);

    boolean existsByNameAndPath(String name, String path);

    /**
     * Used by the service layer to check uniqueness on update
     * (the legacy uniqueness rule on routes is name + path).
     */
    java.util.Optional<Route> findByNameAndPath(String name, String path);

    /**
     * Routes visible to a user whose JWT carries any of the
     * given role ids. The {@code /myMenu} endpoint uses this
     * to compute the per-user sidebar in one round-trip.
     *
     * <p>The query unions two paths:
     * <ol>
     *   <li><b>Broad</b> — the route is a member of an
     *       {@link com.co.eurekatic.common.entity.App App} via
     *       the {@code app_route} M:N (i.e. {@code App.routes},
     *       the "checked" list the App edit page's route picker
     *       controls), and that app is granted to one of the
     *       user's roles via {@code role_app} (the user sees
     *       all routes in that app).</li>
     *   <li><b>Fine-grained</b> — the route has a direct
     *       {@code ROLE_ROUTE} binding for one of the
     *       user's roles, regardless of {@code app_route}
     *       membership. This path catches routes that
     *       intentionally escape any app boundary, plus
     *       ad-hoc per-route grants.</li>
     * </ol>
     *
     * <p><b>Source of truth is {@code app_route}</b> — the M:N
     * that {@code AppService.bindRoute}/{@code unbindRoute} (the
     * App edit page's route picker) exclusively mutate. {@code Route}
     * has no "primary app" FK; {@code app_route} membership is the
     * only relationship between a route and its app(s).
     *
     * <p>Spring Data binds {@code :roleIds} as a Postgres
     * {@code BIGINT[]} via the JPQL {@code IN} clause. The
     * {@code DISTINCT} deduplicates routes that match both
     * paths (a route in an app that ALSO has a direct role
     * binding would otherwise appear twice).
     *
     * <p>Ordering: {@code MENUORDER} ASC breaks ties on
     * {@code ID_ROUTE} ASC — gives a stable order across
     * requests when two routes share the same
     * {@code menuOrder}.
     */
    @Query("""
            SELECT DISTINCT r FROM Route r
            WHERE r.id IN (
                SELECT r2.id FROM App a2 JOIN a2.routes r2 JOIN a2.roles rol2
                 WHERE rol2.id IN :roleIds
                UNION
                SELECT r3.id FROM Route r3 JOIN r3.roles rol3
                 WHERE rol3.id IN :roleIds
            )
            ORDER BY r.menuOrder ASC, r.id ASC
            """)
    List<Route> findVisibleForRoles(@Param("roleIds") Collection<Long> roleIds);

    /**
     * Scoped variant of {@link #findVisibleForRoles(Collection)}:
     * restricts the result to routes that are members (via
     * {@code app_route}) of the given app. Powers the
     * {@code GET /sso-admin/myMenu?app=<name>} endpoint, where the
     * SPA's {@code VITE_APP_NAME} is resolved to an
     * {@link com.co.eurekatic.common.entity.App} id on the
     * backend and used here as the scoping filter.
     *
     * <p>Same {@code app_route}-based source of truth as
     * {@link #findVisibleForRoles(Collection)} — see that
     * method's Javadoc. The {@code appId} predicate is applied
     * to BOTH branches of the inner UNION (not just the broad
     * one): a route can simultaneously satisfy the App-grant
     * branch AND the fine-grained branch, and without the filter
     * on branch 2 a route that's only a member of {@code OtherApp}
     * but ALSO has a direct {@code role_route} binding would leak
     * through when the caller asked for {@code ThisApp}. Adding
     * it to both keeps the scoped result a true intersection of
     * "visible to caller's roles" AND "is a member (via
     * app_route) of the requested app".
     *
     * <p>Same ordering as the un-scoped overload — the response
     * shape is unchanged.
     *
     * <p>Returns empty (not an error) when the caller's roles
     * don't intersect the app's route set; the endpoint surfaces
     * this as {@code 200 + []} per the {@code /myMenu} "what
     * can I see?" contract.
     */
    @Query("""
            SELECT DISTINCT r FROM Route r
            WHERE r.id IN (
                SELECT r2.id FROM App a2 JOIN a2.routes r2 JOIN a2.roles rol2
                 WHERE rol2.id IN :roleIds
                   AND a2.id = :appId
                UNION
                SELECT r3.id FROM Route r3 JOIN r3.roles rol3
                 WHERE rol3.id IN :roleIds
                   AND r3.id IN (
                       SELECT r4.id FROM App a4 JOIN a4.routes r4
                        WHERE a4.id = :appId
                   )
            )
            ORDER BY r.menuOrder ASC, r.id ASC
            """)
    List<Route> findVisibleForRoles(
            @Param("roleIds") Collection<Long> roleIds,
            @Param("appId") Long appId);
}
