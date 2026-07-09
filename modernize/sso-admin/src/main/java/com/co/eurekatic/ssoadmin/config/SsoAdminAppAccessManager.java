package com.co.eurekatic.ssoadmin.config;

import com.co.eurekatic.ssoadmin.service.AppAccessService;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.function.Supplier;

/**
 * Replaces the bare {@code hasRole("ADMIN")} gate on
 * {@code SecurityConfig}'s {@code anyRequest()} rule. A caller must
 * hold {@code ROLE_ADMIN} <em>and</em> at least one of their roles
 * must have a {@code role_app} binding to {@link #appName} (seeded
 * for ADMIN by the V10 migration) — no role, not even ADMIN,
 * bypasses the {@code role_app} check.
 *
 * <p>Without this, {@code role_app} only ever controlled what
 * {@code /myMenu} shows in the sidebar — any caller with a role
 * literally named {@code ADMIN} could reach every business endpoint
 * regardless of whether that role was ever scoped to this app.
 */
public class SsoAdminAppAccessManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final AppAccessService appAccessService;
    private final String appName;

    public SsoAdminAppAccessManager(AppAccessService appAccessService, String appName) {
        this.appAccessService = appAccessService;
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
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String roleName = authority.getAuthority().replaceFirst("^ROLE_", "");
            if (roleName.equals("ADMIN")) {
                hasAdminRole = true;
            }
            if (appAccessService.hasAccess(appName, roleName)) {
                hasAppAccess = true;
            }
        }
        return new AuthorizationDecision(hasAdminRole && hasAppAccess);
    }
}
