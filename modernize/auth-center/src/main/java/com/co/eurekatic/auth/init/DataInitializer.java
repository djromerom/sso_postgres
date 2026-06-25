package com.co.eurekatic.auth.init;

import com.co.eurekatic.common.entity.Role;
import com.co.eurekatic.common.entity.User;
import com.co.eurekatic.common.repository.RoleRepository;
import com.co.eurekatic.common.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the database with a default admin user on first startup so
 * the system is usable out of the box. The seed is idempotent — if
 * an admin user with the configured username already exists, the
 * seeder is a no-op.
 *
 * <p>Configuration (with defaults):
 * <pre>
 * sso:
 *   bootstrap:
 *     admin-username: ${SSO_ADMIN_USERNAME:admin}
 *     admin-password: ${SSO_ADMIN_PASSWORD:admin}
 *     admin-email:    ${SSO_ADMIN_EMAIL:admin@example.com}
 *     enabled:        ${SSO_BOOTSTRAP_ADMIN:true}
 * </pre>
 *
 * <p>Production deployments should set
 * {@code sso.bootstrap.enabled=false} (or leave the password unset
 * and let the seeder refuse to run) and provision users via a
 * dedicated admin endpoint, LDAP, or a one-time migration.
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;
    private final String adminUsername;
    private final String adminPassword;
    private final String adminEmail;

    public DataInitializer(UserRepository userRepository,
                           RoleRepository roleRepository,
                           PasswordEncoder passwordEncoder,
                           @Value("${sso.bootstrap.enabled:true}") boolean enabled,
                           @Value("${sso.bootstrap.admin-username:admin}") String adminUsername,
                           @Value("${sso.bootstrap.admin-password:admin}") String adminPassword,
                           @Value("${sso.bootstrap.admin-email:admin@example.com}") String adminEmail) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.adminEmail = adminEmail;
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            log.info("DataInitializer: sso.bootstrap.enabled=false — skipping admin seed");
            return;
        }
        if (adminPassword == null || adminPassword.isBlank() || "CHANGE_ME".equals(adminPassword)) {
            log.warn("DataInitializer: admin password is unset/placeholder — skipping admin seed. "
                    + "Set SSO_ADMIN_PASSWORD or change sso.bootstrap.admin-password to enable.");
            return;
        }
        if (userRepository.existsByUsername(adminUsername)) {
            log.info("DataInitializer: user '{}' already exists — skipping admin seed", adminUsername);
            return;
        }

        Role adminRole = ensureRole("ADMIN", "Administrator");
        Role userRole = ensureRole("USER", "Standard user");

        User admin = new User();
        admin.setUsername(adminUsername);
        admin.setEmail(adminEmail);
        admin.setFullName("Default Administrator");
        admin.setPassword(passwordEncoder.encode(adminPassword));
        admin.setEnabled(true);
        admin.setActive(true);
        admin.setLdap(false);
        admin.addRole(adminRole);
        admin.addRole(userRole);
        userRepository.save(admin);

        log.info("DataInitializer: created default admin user '{}' with roles [USER, ADMIN]", adminUsername);
    }

    private Role ensureRole(String name, String description) {
        return roleRepository.findByName(name).orElseGet(() -> {
            Role r = new Role();
            r.setName(name);
            r.setDescription(description);
            return roleRepository.save(r);
        });
    }
}
