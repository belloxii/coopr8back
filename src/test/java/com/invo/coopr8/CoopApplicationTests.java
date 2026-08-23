package com.invo.coopr8;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.invo.coopr8.support.AbstractIntegrationTest;

/**
 * The application context comes up, against a throwaway PostgreSQL container.
 *
 * <p>This class used to be a bare {@code @SpringBootTest} with no profile, which meant
 * running it resolved {@code DB_URL} from the project-root {@code .env} file and booted
 * against whatever database that named -- running Flyway there before any assertion could
 * execute. Extending {@link AbstractIntegrationTest} is what makes that impossible now:
 * the datasource is a container, and {@code TestDatasourceGuard} aborts startup if it
 * ever resolves to anything else.
 */
class CoopApplicationTests extends AbstractIntegrationTest {

    @Test
    @DisplayName("Flyway migrates the empty container from scratch and the JPA mapping validates against it")
    void contextLoads() {
        // Reaching this point already proves a great deal: the context refreshed, which
        // means Flyway applied the migrations to an empty database and Hibernate's
        // ddl-auto=validate then agreed that every entity matches the resulting schema.
        List<String> appliedVersions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true AND version IS NOT NULL "
                        + "ORDER BY installed_rank",
                String.class);

        assertThat(appliedVersions)
                .as("the test database must be built by the real migrations, in order, from empty")
                .contains("1");
    }
}
