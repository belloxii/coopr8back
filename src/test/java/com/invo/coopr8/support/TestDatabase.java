package com.invo.coopr8.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The single throwaway PostgreSQL instance every integration test runs against.
 *
 * <p><strong>Why a container and not H2.</strong> COOPR8's tenant isolation is
 * enforced partly by PostgreSQL-specific DDL -- partial unique indexes
 * ({@code ... WHERE psn IS NOT NULL}) and expression indexes
 * ({@code UNIQUE (organization_id, LOWER(email))}). H2 cannot express those, so a
 * suite that passed on H2 would say nothing about whether the constraints that
 * actually protect tenants in production hold. The container runs the real Flyway
 * migrations against real PostgreSQL, so {@code ddl-auto=validate} and every
 * uniqueness test mean what they claim.
 *
 * <p><strong>Lifecycle.</strong> One container per JVM, started lazily on first use
 * and intentionally never stopped: Testcontainers' Ryuk sidecar reaps it when the JVM
 * exits. Sharing it across test classes avoids paying a PostgreSQL boot per class;
 * {@code AbstractIntegrationTest} truncates the tenant tables before each test, so
 * sharing costs no isolation between tests.
 *
 * <p><strong>Safety.</strong> The container is started here and nowhere else, and
 * {@link #startedJdbcUrl()} is what {@link TestDatasourceGuard} compares the resolved
 * datasource URL against. That comparison is what makes it impossible for a test to
 * connect to a real database -- see {@code application-test.properties} for the full
 * three-layer argument.
 *
 * <p>The image is overridable, for parity with whatever major version a deployment
 * runs, via the {@code coopr8.test.postgres.image} system property or the
 * {@code COOPR8_TEST_POSTGRES_IMAGE} environment variable.
 */
public final class TestDatabase {

    private static final String DEFAULT_IMAGE = "postgres:16-alpine";
    private static final String IMAGE_SYSTEM_PROPERTY = "coopr8.test.postgres.image";
    private static final String IMAGE_ENVIRONMENT_VARIABLE = "COOPR8_TEST_POSTGRES_IMAGE";

    /** Names chosen to be obviously disposable, so a stray log line is unmistakable. */
    private static final String DATABASE_NAME = "coopr8_test";
    private static final String USERNAME = "coopr8_test_user";
    private static final String PASSWORD = "coopr8_test_password";

    private static PostgreSQLContainer<?> container;

    private TestDatabase() {
    }

    /**
     * Starts the container on first call and returns it thereafter.
     *
     * <p>Must not be called from a static initialiser: Testcontainers' "is Docker
     * available" check has to be able to disable a test class BEFORE anything tries to
     * start a container, and class loading happens before that check. Callers reach this
     * from {@code @DynamicPropertySource}, which runs only for classes that were not
     * disabled.
     */
    public static synchronized PostgreSQLContainer<?> started() {
        if (container == null) {
            PostgreSQLContainer<?> instance = new PostgreSQLContainer<>(DockerImageName.parse(image()))
                    .withDatabaseName(DATABASE_NAME)
                    .withUsername(USERNAME)
                    .withPassword(PASSWORD);
            instance.start();
            container = instance;
        }
        return container;
    }

    /**
     * The JDBC URL of the container this JVM started, or {@code null} if none has been
     * started. Deliberately does NOT start one: the guard uses this to detect the case
     * where a context is coming up against a datasource nobody in this JVM provisioned.
     */
    public static synchronized String startedJdbcUrl() {
        return container == null ? null : container.getJdbcUrl();
    }

    /**
     * Points the Spring test context at the container.
     *
     * <p>{@code @DynamicPropertySource} property sources sit above every other source in
     * a test environment -- above {@code application-test.properties} and above the
     * {@code .env} file that {@code application.properties} imports -- so this is what
     * decides which database the test talks to.
     */
    public static void registerDatasource(DynamicPropertyRegistry registry) {
        PostgreSQLContainer<?> instance = started();
        registry.add("spring.datasource.url", instance::getJdbcUrl);
        registry.add("spring.datasource.username", instance::getUsername);
        registry.add("spring.datasource.password", instance::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    private static String image() {
        String fromSystemProperty = System.getProperty(IMAGE_SYSTEM_PROPERTY);
        if (fromSystemProperty != null && !fromSystemProperty.isBlank()) {
            return fromSystemProperty.trim();
        }
        String fromEnvironment = System.getenv(IMAGE_ENVIRONMENT_VARIABLE);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment.trim();
        }
        return DEFAULT_IMAGE;
    }
}
