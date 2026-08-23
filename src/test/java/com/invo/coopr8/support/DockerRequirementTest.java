package com.invo.coopr8.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

/**
 * Turns "the security tests were skipped" into a build failure when the build says it
 * requires them.
 *
 * <p>{@code AbstractIntegrationTest} is annotated {@code @Testcontainers(disabledWithoutDocker
 * = true)}, so on a machine with no Docker daemon the tenant-isolation suite is reported as
 * SKIPPED. That is the right default for local work -- a developer without Docker can still
 * run the unit and architecture tests -- but it is dangerous as a release gate, because a
 * green build with every isolation test silently skipped looks identical to a green build
 * that actually proved isolation.
 *
 * <p>CI (and any run whose result is used to justify a deployment) must therefore set
 * {@code -Dcoopr8.test.require-docker=true}, or the {@code COOPR8_TEST_REQUIRE_DOCKER=true}
 * environment variable. This test then fails loudly if Docker is missing, instead of the
 * suite quietly evaporating.
 */
class DockerRequirementTest {

    private static final String REQUIRE_DOCKER_SYSTEM_PROPERTY = "coopr8.test.require-docker";
    private static final String REQUIRE_DOCKER_ENVIRONMENT_VARIABLE = "COOPR8_TEST_REQUIRE_DOCKER";

    @Test
    @DisplayName("Docker is available, so the tenant-isolation suite can actually run")
    void dockerIsAvailableWhenTheBuildRequiresIt() {
        assumeTrue(dockerIsRequired(), () ->
                "Not gating on Docker: set -D" + REQUIRE_DOCKER_SYSTEM_PROPERTY + "=true (or "
                        + REQUIRE_DOCKER_ENVIRONMENT_VARIABLE + "=true) for any run whose result is "
                        + "used to justify a deployment. Without it, the Testcontainers-backed "
                        + "tenant-isolation tests are SKIPPED rather than run, and a skip is not a pass.");

        assertThat(DockerClientFactory.instance().isDockerAvailable())
                .as("Docker daemon must be reachable: " + REQUIRE_DOCKER_SYSTEM_PROPERTY + " is set, "
                        + "which means this build claims to verify tenant isolation. Without Docker the "
                        + "Testcontainers-backed integration, authorization and cross-tenant (IDOR) tests "
                        + "do not execute at all.")
                .isTrue();
    }

    private static boolean dockerIsRequired() {
        String fromSystemProperty = System.getProperty(REQUIRE_DOCKER_SYSTEM_PROPERTY);
        if (fromSystemProperty != null && !fromSystemProperty.isBlank()) {
            return Boolean.parseBoolean(fromSystemProperty.trim());
        }
        String fromEnvironment = System.getenv(REQUIRE_DOCKER_ENVIRONMENT_VARIABLE);
        return fromEnvironment != null && Boolean.parseBoolean(fromEnvironment.trim());
    }
}
