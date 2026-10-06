package com.handoff.auth;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds a demo organization and four users (one per role) on startup.
 * Active ONLY under the "dev" profile. Never runs in tests or CI.
 */
@Component
@Profile("dev")
@org.springframework.core.annotation.Order(1)
public class DevAuthDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevAuthDataSeeder.class);
    private static final String DEMO_ORG_NAME = "Acme Support";
    private static final String DEFAULT_PASSWORD = "Password123!";

    private final AuthRepository authRepository;
    private final PasswordEncoder passwordEncoder;

    public DevAuthDataSeeder(AuthRepository authRepository, PasswordEncoder passwordEncoder) {
        this.authRepository = authRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (authRepository.findOrganizationByName(DEMO_ORG_NAME).isPresent()) {
            log.info("Demo organization '{}' already exists, skipping dev seed.", DEMO_ORG_NAME);
            return;
        }

        log.info("Seeding dev accounts for '{}'...", DEMO_ORG_NAME);

        UUID orgId = UUID.randomUUID();
        authRepository.createOrganization(orgId, DEMO_ORG_NAME);

        String passwordHash = passwordEncoder.encode(DEFAULT_PASSWORD);

        seedUser(orgId, "admin@example.com", "Alice Admin", Role.ADMIN, passwordHash);
        seedUser(orgId, "approver@example.com", "Ravi Approver", Role.APPROVER, passwordHash);
        seedUser(orgId, "operator@example.com", "Maya Operator", Role.OPERATOR, passwordHash);
        seedUser(orgId, "viewer@example.com", "Arjun Viewer", Role.VIEWER, passwordHash);

        log.info("Dev seed completed: 4 accounts created for '{}' (password: '{}')", DEMO_ORG_NAME, DEFAULT_PASSWORD);
    }

    private void seedUser(UUID orgId, String email, String displayName, Role role, String passwordHash) {
        UUID userId = UUID.randomUUID();
        authRepository.createUser(userId, email, passwordHash, displayName);
        authRepository.createMembership(userId, orgId, role);
    }
}
