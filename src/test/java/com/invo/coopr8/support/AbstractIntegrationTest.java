package com.invo.coopr8.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invo.coopr8.tenant.TenantContext;

/**
 * Base class for every COOPR8 integration and tenant-security test.
 *
 * <p><strong>What it guarantees.</strong>
 * <ul>
 *   <li><em>Never touches a real database.</em> {@code @DynamicPropertySource} points the
 *       context at a throwaway PostgreSQL container, and {@link TestDatasourceGuard}
 *       aborts context startup if the resolved URL is anything else. See
 *       {@code application-test.properties} for the full argument.</li>
 *   <li><em>Exercises the real security chain.</em> {@code @AutoConfigureMockMvc} without
 *       {@code addFilters = false}, so requests pass through {@code JwtTokenValidator} and
 *       Spring Security exactly as a real HTTP call would. That is what makes the
 *       cross-tenant (IDOR) tests API-level rather than service-level.</li>
 *   <li><em>Starts from a clean, empty database each test.</em> Tenant tables are
 *       truncated before each test, so no test can pass because of another test's rows.</li>
 *   <li><em>Starts with no ambient identity.</em> {@link TenantContext} and the Spring
 *       {@code SecurityContext} are cleared before and after every test, so a test that
 *       accidentally relies on leaked context fails.</li>
 * </ul>
 *
 * <p><strong>Docker.</strong> {@code disabledWithoutDocker = true} means these classes are
 * reported as SKIPPED, not passed, where no Docker daemon is available. A skip is not a
 * pass -- {@code DockerRequirementTest} turns the skip into a hard failure when
 * {@code -Dcoopr8.test.require-docker=true} is set, which is how CI should run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ContextConfiguration(classes = TestSupportConfig.class, initializers = TestDatasourceGuard.class)
@AutoConfigureMockMvc
@ActiveProfiles(TestDatasourceGuard.TEST_PROFILE)
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractIntegrationTest {

    /**
     * Every tenant-owned table plus {@code organizations}, most-dependent first.
     * {@code CASCADE} makes the order redundant, but naming them explicitly documents the
     * full set of tenant-owned tables -- if a new one is added and not listed here, tests
     * start leaking rows into each other, which is the failure we want to be noisy.
     * {@code flyway_schema_history} is deliberately absent: the schema stays migrated.
     *
     * <p><strong>The Phase 4 configuration tables are truncated too, and that has a
     * consequence worth knowing.</strong> {@code V9__phase4_seed_defaults.sql} seeded one
     * configuration row per organization <em>once, at migration time</em>. Truncating removes
     * those rows, so an organization a test creates afterwards has no configuration -- the same
     * state a cooperative onboarded after Stage 1 would be in. Any test that needs
     * configuration must insert it, which is the correct thing to exercise: provisioning is a
     * code path, not a migration artefact.
     *
     * <p>{@code organization_config_audit} is append-only and refuses {@code UPDATE} and
     * {@code DELETE} via {@code tr_organization_config_audit_append_only}. That trigger does
     * not fire on {@code TRUNCATE}, deliberately: blocking truncation would force this method
     * to disable the trigger on every test, and a safeguard the test suite routinely switches
     * off is worse than none. {@code TRUNCATE} requires table ownership the application role
     * does not hold.
     */
    private static final String TRUNCATE_TENANT_TABLES = """
            TRUNCATE TABLE notification, otp, repay, saving, shares, loan,
                           payment_transaction, organization_payment_config,
                           organization_config_audit,
                           organization_loan_type_exclusion,
                           organization_loan_config, organization_loan_type,
                           organization_savings_plan, organization_shares_config,
                           organization_repayment_config, organization_membership_config,
                           users, organizations
            RESTART IDENTITY CASCADE
            """;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected RecordingEmailService recordedEmails;

    @DynamicPropertySource
    static void testcontainersDatasource(DynamicPropertyRegistry registry) {
        TestDatabase.registerDatasource(registry);
    }

    @BeforeEach
    void resetDatabaseAndAmbientState() {
        clearAmbientState();
        recordedEmails.clear();
        jdbcTemplate.execute(TRUNCATE_TENANT_TABLES);
    }

    @AfterEach
    void clearAmbientStateAfterTest() {
        clearAmbientState();
    }

    private void clearAmbientState() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }
}
