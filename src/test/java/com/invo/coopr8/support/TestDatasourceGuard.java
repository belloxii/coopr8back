package com.invo.coopr8.support;

import java.util.Arrays;
import java.util.List;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Refuses to start a test application context unless it is pointed at the throwaway
 * Testcontainers PostgreSQL instance.
 *
 * <p><strong>The risk this closes.</strong> {@code application.properties} imports the
 * project-root {@code .env} file, which on a developer machine holds real
 * {@code DB_URL}/{@code DB_USERNAME}/{@code DB_PASSWORD} values. A bare
 * {@code @SpringBootTest} therefore boots against whatever database that file names --
 * and because Flyway runs at context refresh, the damage would be done before a single
 * assertion executed. Property-source precedence rules are subtle enough that "the test
 * profile should win" is not a safety argument. This guard does not reason about
 * precedence at all: it compares the fully resolved URL against the container this JVM
 * started, and fails the context if they differ.
 *
 * <p><strong>Why an initialiser.</strong> {@code ApplicationContextInitializer}s declared
 * via {@code @ContextConfiguration(initializers = ...)} run after Spring's context
 * customizers have contributed {@code @DynamicPropertySource} values, and before any bean
 * is instantiated. So the environment is final, and nothing -- not Flyway, not the
 * connection pool -- has touched a database yet.
 *
 * <p><strong>Credential hygiene.</strong> On failure this reports only that the resolved
 * URL is not the container's. It never echoes the offending value, because that value is
 * precisely the one likely to embed production credentials, and a failed build's log is
 * not a safe place for them.
 */
public class TestDatasourceGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static final String TEST_PROFILE = "test";

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        ConfigurableEnvironment environment = applicationContext.getEnvironment();

        List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
        if (!activeProfiles.contains(TEST_PROFILE)) {
            throw new IllegalStateException(
                    "REFUSING TO START TEST CONTEXT: the '" + TEST_PROFILE + "' profile is not active "
                            + "(active: " + activeProfiles + "). Integration tests must extend "
                            + "AbstractIntegrationTest or declare @ActiveProfiles(\"" + TEST_PROFILE + "\"), "
                            + "otherwise the context resolves the production datasource from .env.");
        }

        String containerUrl = TestDatabase.startedJdbcUrl();
        if (containerUrl == null) {
            throw new IllegalStateException(
                    "REFUSING TO START TEST CONTEXT: no Testcontainers PostgreSQL instance has been "
                            + "started in this JVM, so the context would connect to whatever datasource "
                            + "the environment happens to name. The test class must contribute "
                            + "TestDatabase.registerDatasource(registry) from a @DynamicPropertySource "
                            + "method (AbstractIntegrationTest does this).");
        }

        String resolvedUrl = environment.getProperty("spring.datasource.url");
        if (!containerUrl.equals(resolvedUrl)) {
            // The resolved value is withheld on purpose -- it is the one most likely to
            // contain production credentials.
            throw new IllegalStateException(
                    "REFUSING TO START TEST CONTEXT: the resolved spring.datasource.url is NOT the "
                            + "throwaway Testcontainers instance (expected " + containerUrl + "; the "
                            + "configured value is withheld because it may contain credentials). Some "
                            + "property source is overriding the container URL -- most likely the "
                            + "project-root .env file imported by application.properties. No test is "
                            + "permitted to run against a database this JVM did not provision.");
        }
    }
}
