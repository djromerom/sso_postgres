package com.co.eurekatic.ssoadmin.config;

import com.co.eurekatic.ssoadmin.service.AppAccessService;
import com.co.eurekatic.ssoadmin.service.EndpointAccessService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Replaces the bare {@code hasRole("ADMIN")} gate on
 * {@code SecurityConfig}'s {@code anyRequest()} rule.
 *
 * <p>Two checks, in order:
 * <ol>
 *   <li>at least one of the caller's roles must have a
 *       {@code role_app} binding to {@link #appName} (seeded for
 *       ADMIN by the V10 migration) — no role, not even ADMIN,
 *       bypasses this. Without it, {@code role_app} only ever
 *       controlled what {@code /myMenu} shows in the sidebar; any
 *       caller with a role literally named {@code ADMIN} could
 *       reach every business endpoint regardless of whether that
 *       role was ever scoped to this app.</li>
 *   <li>having passed that: {@code ADMIN} is an unconditional
 *       bypass for everything past this point (matches every
 *       endpoint, same as before this class gained endpoint-level
 *       checks — a misconfigured {@code role_endpoint} row can
 *       never lock ADMIN out of its own console). Any other role
 *       must additionally have a {@code role_endpoint} binding
 *       matching this specific request's method + path (see
 *       {@link EndpointAccessService}) — this is what lets a
 *       non-ADMIN role get scoped access to a handful of endpoints
 *       without needing full ADMIN authority.</li>
 * </ol>
 */
public class SsoAdminAccessManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final AppAccessService appAccessService;
    private final EndpointAccessService endpointAccessService;
    private final String appName;

    public SsoAdminAccessManager(AppAccessService appAccessService,
                                  EndpointAccessService endpointAccessService,
                                  String appName) {
        this.appAccessService = appAccessService;
        this.endpointAccessService = endpointAccessService;
        this.appName = appName;
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authSupplier, RequestAuthorizationContext ctx) {
        Authentication auth = authSupplier.get();
        if (auth == null || !auth.isAuthenticated()) {
            return new AuthorizationDecision(false);
        }

        boolean hasAdminRole = false;
        boolean hasAppAccess = false;
        Set<String> roleNames = new LinkedHashSet<>();
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String roleName = authority.getAuthority().replaceFirst("^ROLE_", "");
            roleNames.add(roleName);
            if (roleName.equals("ADMIN")) {
                hasAdminRole = true;
            }
            if (appAccessService.hasAccess(appName, roleName)) {
                hasAppAccess = true;
            }
        }
        if (!hasAppAccess) {
            return new AuthorizationDecision(false);
        }
        if (hasAdminRole) {
            return new AuthorizationDecision(true);
        }

        HttpServletRequest request = ctx.getRequest();
        boolean hasEndpointAccess =
                endpointAccessService.hasAccess(request.getMethod(), request.getRequestURI(), roleNames);
        return new AuthorizationDecision(hasEndpointAccess);
    }
}
