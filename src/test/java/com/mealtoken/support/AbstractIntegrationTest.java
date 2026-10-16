package com.mealtoken.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests. Uses a real PostgreSQL:
 *  - if TEST_DB_URL (+ TEST_DB_USERNAME / TEST_DB_PASSWORD) is set, that database is used (CI without Docker);
 *  - otherwise a throw-away Testcontainers PostgreSQL 16 is started (requires Docker).
 * Tests create uniquely named data, so they never depend on a clean database.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestClockConfig.class, Fixtures.class})
public abstract class AbstractIntegrationTest {

    private static PostgreSQLContainer<?> container;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        String url = System.getenv("TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            r.add("spring.datasource.url", () -> url);
            r.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USERNAME", "postgres"));
            r.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
            return;
        }
        synchronized (AbstractIntegrationTest.class) {
            if (container == null) {
                container = new PostgreSQLContainer<>("postgres:16");
                container.start();
            }
        }
        r.add("spring.datasource.url", container::getJdbcUrl);
        r.add("spring.datasource.username", container::getUsername);
        r.add("spring.datasource.password", container::getPassword);
    }
}
