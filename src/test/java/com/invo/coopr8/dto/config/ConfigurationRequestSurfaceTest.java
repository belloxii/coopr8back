package com.invo.coopr8.dto.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves no configuration request lets a caller name the cooperative being configured.
 *
 * <h2>The rule</h2>
 * §3.5 of {@code docs/phase4-tenant-business-configuration.md}: a request DTO carries no tenant
 * field. The cooperative whose rules are being edited is the one in the caller's verified token, and
 * nothing else. The moment a request body carries an {@code organizationId}, the server has two
 * candidate answers to "whose configuration is this?" -- one the caller supplied and one the token
 * proved -- and every line of code that reads the wrong one is a cross-tenant write. Not accepting
 * the field is what makes that class of bug unwritable rather than merely unwise.
 *
 * <p>The same reasoning is why the payment work refuses to trust
 * {@code {organizationId, subaccount}} from a client: a caller who can name the destination can name
 * somebody else's.
 *
 * <h2>Why the class list is cross-checked against the directory</h2>
 * A hard-coded list of DTOs is a test that silently stops covering anything the day somebody adds a
 * DTO and does not add it here. {@link #everyRequestDtoInThePackageIsUnderTest()} lists
 * {@code src/main/java/com/invo/coopr8/dto/config} and fails if it finds a {@code *Request.java} this
 * class does not name -- so the coverage gap is a build failure rather than a quiet omission.
 *
 * <p>No database, no Docker, no Spring context: reflection over records.
 */
class ConfigurationRequestSurfaceTest {

    /** Where the request DTOs live, relative to the module root. */
    private static final Path REQUEST_DTO_DIRECTORY =
            Path.of("src", "main", "java", "com", "invo", "coopr8", "dto", "config");

    /** Every request DTO an administrator can submit to {@code /api/admin/config/**}. */
    private static final List<Class<?>> REQUEST_DTOS = List.of(
            LoanConfigRequest.class,
            LoanTypeRequest.class,
            LoanTypeExclusionRequest.class,
            SavingsPlanRequest.class,
            SharesConfigRequest.class,
            RepaymentConfigRequest.class,
            MembershipConfigRequest.class);

    /**
     * Component names that would let a caller choose the tenant.
     *
     * <p>Compared against the whole lower-cased component name, and also as a substring, so
     * {@code organizationId}, {@code organization}, {@code orgId}, {@code tenantId} and
     * {@code targetOrganization} are all caught. Substring matching is deliberately blunt here: a
     * false positive costs somebody a rename, and a false negative costs a tenant boundary.
     */
    private static final List<String> FORBIDDEN_FRAGMENTS = List.of(
            "organization", "organisation", "tenant", "orgid", "orgslug", "cooperativeid");

    /**
     * Settings a tenant administrator may never set, whatever the endpoint.
     *
     * <p>Class C of the architecture document, plus Decision 6's penalty prohibition. The entities
     * are already checked for these by {@code OrganizationConfigurationModelTest}; a request DTO is
     * the other place one could appear, because a field that never reaches a column is still a field
     * the API accepts and a screen can show.
     */
    private static final List<String> FORBIDDEN_SETTING_FRAGMENTS = List.of(
            "password", "passwd", "secret", "token", "credential", "jwt", "regex",
            "sessiontimeout", "signingkey", "subscription", "batchlimit",
            "penalty", "penalt", "latefee", "latecharge");

    static Stream<Class<?>> requestDtos() {
        return REQUEST_DTOS.stream();
    }

    // ------------------------------------------------------------------ the tenant rule

    @ParameterizedTest
    @MethodSource("requestDtos")
    @DisplayName("No configuration request declares the cooperative it applies to")
    void noRequestDtoCarriesATenantField(Class<?> dto) {
        assertThat(dto.isRecord())
                .as("%s must be a record: a mutable request object can be reassigned between "
                        + "validation and use", dto.getSimpleName())
                .isTrue();

        for (RecordComponent component : dto.getRecordComponents()) {
            String name = component.getName().toLowerCase(Locale.ROOT);
            for (String forbidden : FORBIDDEN_FRAGMENTS) {
                assertThat(name)
                        .as("%s.%s would let a caller name the cooperative being configured. The "
                                + "tenant comes from the verified token and from nowhere else -- see "
                                + "§3.5. If this field is genuinely something else, rename it.",
                                dto.getSimpleName(), component.getName())
                        .doesNotContain(forbidden);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("requestDtos")
    @DisplayName("No configuration request accepts a platform security control or a penalty")
    void noRequestDtoCarriesAClassCSettingOrAPenalty(Class<?> dto) {
        for (RecordComponent component : dto.getRecordComponents()) {
            String name = component.getName().toLowerCase(Locale.ROOT);
            for (String forbidden : FORBIDDEN_SETTING_FRAGMENTS) {
                assertThat(name)
                        .as("%s.%s is a platform-level control, not a tenant setting. A cooperative "
                                + "administrator who could change it would be changing it for their "
                                + "own members.", dto.getSimpleName(), component.getName())
                        .doesNotContain(forbidden);
            }
        }
    }

    // ------------------------------------------------------------------ the coverage guard

    @Test
    @DisplayName("Every request DTO in the package is covered by this test")
    void everyRequestDtoInThePackageIsUnderTest() throws IOException {
        assertThat(REQUEST_DTO_DIRECTORY)
                .as("this test locates the DTOs by path, so it fails loudly rather than vacuously "
                        + "if the package moves")
                .exists();

        List<String> onDisk;
        try (Stream<Path> files = Files.list(REQUEST_DTO_DIRECTORY)) {
            onDisk = files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith("Request.java"))
                    .map(name -> name.substring(0, name.length() - ".java".length()))
                    .sorted()
                    .toList();
        }

        List<String> covered = REQUEST_DTOS.stream()
                .map(Class::getSimpleName)
                .sorted()
                .toList();

        assertThat(onDisk)
                .as("a request DTO nobody checks is a request DTO that can grow an organizationId. "
                        + "Add it to REQUEST_DTOS.")
                .isEqualTo(covered);

        assertThat(onDisk)
                .as("if this is empty the assertions above are testing nothing")
                .isNotEmpty();
    }

    // ------------------------------------------------------------------ the reason field

    @ParameterizedTest
    @MethodSource("requestDtos")
    @DisplayName("Every configuration request can carry the administrator's reason")
    void everyRequestDtoCanCarryAReason(Class<?> dto) {
        boolean hasReason = Stream.of(dto.getRecordComponents())
                .anyMatch(component -> component.getName().equals("reason")
                        && component.getType() == String.class);

        assertThat(hasReason)
                .as("%s must accept a reason. §7 requires one for rate and price changes and keeps "
                        + "it where offered elsewhere; a DTO with no field for it makes the "
                        + "corresponding change impossible to justify.", dto.getSimpleName())
                .isTrue();
    }
}
