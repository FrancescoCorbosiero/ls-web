package it.logisticasanchez.web.support;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class of every integration test that needs the database: activates the {@code test}
 * profile and points the datasource at {@link TestDatabase} (the {@code TEST_DATABASE_*}
 * environment, or a Testcontainers {@code postgres:16}). Subclasses add {@code @SpringBootTest}
 * with the web environment they need.
 */
@ActiveProfiles("test")
public abstract class AbstractPostgresIT {

    @DynamicPropertySource
    static void testDatabaseProperties(DynamicPropertyRegistry registry) {
        TestDatabase.Connection database = TestDatabase.get();
        registry.add("spring.datasource.url", database::url);
        registry.add("spring.datasource.username", database::username);
        registry.add("spring.datasource.password", database::password);
    }
}
