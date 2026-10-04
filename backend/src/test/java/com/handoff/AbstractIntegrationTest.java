package com.handoff;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests.
 *
 * Starts real PostgreSQL 16 and Redis 7 containers once, then reuses them
 * for every test class that extends this base.  Spring Boot auto-runs
 * Flyway on startup, so the database has the V1 tables before tests execute.
 *
 * How it works:
 * - The static {} block runs the first time any subclass is loaded.
 *   It starts Docker containers via Testcontainers.
 * - @DynamicPropertySource overrides application.yml with the
 *   containers' actual ports and credentials so Spring connects to them.
 * - RANDOM_PORT starts a real embedded Tomcat on a free port,
 *   which we need for the WebSocket smoke test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @SuppressWarnings("resource") // container is closed by Testcontainers shutdown hook
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("handoff.jwt.secret", () -> "test-secret-key-must-be-at-least-32-bytes-long-for-testing!");
    }

    @org.junit.jupiter.api.BeforeEach
    void setUpTestRestTemplate(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            org.springframework.boot.test.web.client.TestRestTemplate restTemplate
    ) {
        if (restTemplate != null) {
            restTemplate.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        }
    }
}
