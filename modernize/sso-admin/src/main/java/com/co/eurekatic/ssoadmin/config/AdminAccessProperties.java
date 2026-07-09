package com.co.eurekatic.ssoadmin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * App-scoped access control properties. Bound from
 * {@code sso.admin.*} in {@code application.yml}.
 *
 * @param appName            The {@code App.name} row (seeded by the
 *                           V10 migration) that identifies THIS
 *                           console. {@link com.co.eurekatic.ssoadmin.config.SsoAdminAppAccessManager}
 *                           requires the caller to hold ROLE_ADMIN
 *                           AND have a role bound (role_app) to this
 *                           app. Distinct from {@code sso.email.app-name}
 *                           (branding text for outgoing emails).
 * @param appAccessCacheTtl  TTL for the app-access cache
 *                           ({@link com.co.eurekatic.ssoadmin.service.AppAccessService}).
 *                           A safety net for out-of-band DB edits —
 *                           {@code AppService.bindRole}/{@code unbindRole}
 *                           evict the relevant entry immediately on
 *                           write, so this doesn't need to be short.
 */
@ConfigurationProperties(prefix = "sso.admin")
public record AdminAccessProperties(
        String appName,
        Duration appAccessCacheTtl
) {
    public AdminAccessProperties {
        if (appName == null || appName.isBlank()) appName = "SSO-ADMIN";
        if (appAccessCacheTtl == null) appAccessCacheTtl = Duration.ofMinutes(5);
    }
}
