package com.invo.coopr8.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

/**
 * Pins the invariants of the Phase 4 business-configuration model that no other test can see.
 *
 * <p>{@code SchemaGenTest} proves the DDL Hibernate expects matches the Flyway migrations, and
 * {@code TenantFilterMappingTest} proves every configuration entity carries the tenant filter.
 * Neither can express the rules that come from the <em>decisions</em> rather than from the schema:
 * that a rate column is {@code numeric(6,3)} and not the default {@code numeric(38,2)}, that no
 * security control and no penalty is representable as configuration at all, that the audit trail
 * has no mutable field, and that reducing-balance interest exists in the enum but is not offered to
 * anybody. Each of those is a sentence in the approved architecture that a future change could
 * violate without breaking anything else.
 *
 * <p>No database, no Docker, no Spring context: this is reflection over the annotated classes.
 */
class OrganizationConfigurationModelTest {

    /** The seven configurable domains, plus the audit that records changes to them. */
    private static final List<Class<?>> CONFIGURATION_ENTITIES = List.of(
            OrganizationLoanConfig.class,
            OrganizationLoanType.class,
            OrganizationLoanTypeExclusion.class,
            OrganizationSavingsPlan.class,
            OrganizationSharesConfig.class,
            OrganizationRepaymentConfig.class,
            OrganizationMembershipConfig.class,
            OrganizationConfigAudit.class);

    /**
     * Substrings that must never appear in a configuration field name.
     *
     * <p>The first group is the Class C prohibition: password rules, token lifetimes, signing keys
     * and session policy are platform security controls, and a cooperative administrator who could
     * weaken them for their own tenant would be able to weaken them for their own members. They are
     * not settings.
     *
     * <p>{@code penalty} is here for a different reason -- Decision 6 deferred penalties entirely,
     * and specifically ruled out "a penalty setting that is displayed but not actually enforced". A
     * column with nothing charging against it is exactly that, so the column may not exist until
     * the enforcement does.
     *
     * <p>{@code passport} is deliberately absent from this list even though it reads like a
     * credential: {@code requirePassport} governs whether a member must upload a photograph during
     * onboarding, which is a document, not a secret.
     */
    private static final List<String> FORBIDDEN_FIELD_NAME_FRAGMENTS = List.of(
            "password", "passwd", "secret", "token", "credential", "jwt", "regex",
            "sessiontimeout", "signingkey",
            "penalty", "penalt", "latefee", "latecharge");

    static Stream<Class<?>> configurationEntities() {
        return CONFIGURATION_ENTITIES.stream();
    }

    // ------------------------------------------------------------------ tenancy

    @ParameterizedTest
    @MethodSource("configurationEntities")
    void everyConfigurationEntityIsAnEntityWithAnExplicitTableName(Class<?> entity) {
        assertThat(entity.isAnnotationPresent(Entity.class))
                .as("%s must be a JPA entity", entity.getSimpleName())
                .isTrue();

        // Explicit, so the Flyway DDL and Hibernate `validate` agree regardless of the physical
        // naming strategy in effect -- the same reason Organization declares its own.
        assertThat(entity.getAnnotation(Table.class))
                .as("%s must name its table explicitly", entity.getSimpleName())
                .isNotNull();
        assertThat(entity.getAnnotation(Table.class).name()).isNotBlank();
    }

    @ParameterizedTest
    @MethodSource("configurationEntities")
    void everyConfigurationEntityIsOwnedByExactlyOneOrganization(Class<?> entity) {
        Field organization = field(entity, "organization");

        assertThat(organization.getType())
                .as("%s must reference the owning cooperative, not carry a bare id",
                        entity.getSimpleName())
                .isEqualTo(Organization.class);

        JoinColumn joinColumn = organization.getAnnotation(JoinColumn.class);
        assertThat(joinColumn)
                .as("%s.organization must map the tenant discriminator column explicitly",
                        entity.getSimpleName())
                .isNotNull();
        assertThat(joinColumn.name()).isEqualTo("organization_id");
        assertThat(joinColumn.nullable())
                .as("a configuration row with no cooperative is a row every tenant would match; "
                        + "%s.organization_id must be NOT NULL", entity.getSimpleName())
                .isFalse();
    }

    // ------------------------------------------------------------------ money and rates

    /**
     * The reason this test exists: Hibernate 6 maps {@code BigDecimal} to {@code numeric(38,2)}
     * unless told otherwise, which is where the platform's money convention came from and is the
     * wrong shape for a percentage. A rate column added without explicit precision would silently
     * take the money precision, and {@code numeric(38,2)} cannot hold 12.375%.
     */
    @ParameterizedTest
    @MethodSource("configurationEntities")
    void everyRateFieldDeclaresTheRatePrecisionExplicitly(Class<?> entity) {
        for (Field field : entity.getDeclaredFields()) {
            if (!field.getName().toLowerCase().contains("rate")) {
                continue;
            }
            Column column = field.getAnnotation(Column.class);
            assertThat(column)
                    .as("%s.%s is a rate and must declare its own precision",
                            entity.getSimpleName(), field.getName())
                    .isNotNull();
            assertThat(column.precision())
                    .as("%s.%s must be numeric(6,3); without an explicit precision Hibernate "
                            + "emits the money default numeric(38,2) and `validate` fails on boot",
                            entity.getSimpleName(), field.getName())
                    .isEqualTo(6);
            assertThat(column.scale())
                    .as("%s.%s must be numeric(6,3): three decimal places of a percent",
                            entity.getSimpleName(), field.getName())
                    .isEqualTo(3);
        }
    }

    @ParameterizedTest
    @MethodSource("configurationEntities")
    void noMonetaryOrRateFieldUsesBinaryFloatingPoint(Class<?> entity) {
        for (Field field : entity.getDeclaredFields()) {
            assertThat(field.getType())
                    .as("%s.%s -- money and rates are BigDecimal on this platform; a double "
                            + "cannot represent one kobo exactly and the final installment is "
                            + "calculated by subtraction", entity.getSimpleName(), field.getName())
                    .isNotIn(double.class, Double.class, float.class, Float.class);
        }
    }

    @Test
    void amountsAreBigDecimalWhereverAnAmountIsConfigured() {
        List<String> amountFields = new ArrayList<>();
        for (Class<?> entity : CONFIGURATION_ENTITIES) {
            for (Field field : entity.getDeclaredFields()) {
                String name = field.getName().toLowerCase();
                if (name.contains("amount") || name.contains("price") || name.contains("tolerance")) {
                    assertThat(field.getType())
                            .as("%s.%s holds naira", entity.getSimpleName(), field.getName())
                            .isEqualTo(BigDecimal.class);
                    amountFields.add(entity.getSimpleName() + "." + field.getName());
                }
            }
        }

        assertThat(amountFields)
                .as("finding no configured amounts means this test resolved nothing")
                .isNotEmpty();
    }

    // ------------------------------------------------------------------ enums

    @ParameterizedTest
    @MethodSource("configurationEntities")
    void everyEnumIsPersistedByNameNeverByOrdinal(Class<?> entity) {
        for (Field field : entity.getDeclaredFields()) {
            if (!field.getType().isEnum()) {
                continue;
            }
            Enumerated enumerated = field.getAnnotation(Enumerated.class);
            assertThat(enumerated)
                    .as("%s.%s is an enum; without @Enumerated it defaults to ORDINAL and "
                            + "reordering the constants silently rewrites existing rows",
                            entity.getSimpleName(), field.getName())
                    .isNotNull();
            assertThat(enumerated.value()).isEqualTo(EnumType.STRING);
        }
    }

    @Test
    void reducingBalanceInterestExistsInTheSchemaButIsOfferedToNobody() {
        // Decision 1: keep the constant so the schema need not be redesigned when reducing-balance
        // support arrives, but do not expose it. Every admin-facing list is built from the flag.
        assertThat(InterestMethod.values())
                .as("the persisted enum must keep all three constants")
                .containsExactly(InterestMethod.NONE, InterestMethod.FLAT,
                        InterestMethod.REDUCING_BALANCE);

        assertThat(Arrays.stream(InterestMethod.values())
                .filter(InterestMethod::isAdminSelectable)
                .toList())
                .as("only NONE and FLAT are implemented, so only those two may be selectable; "
                        + "offering REDUCING_BALANCE would let an administrator configure interest "
                        + "no code can calculate")
                .containsExactly(InterestMethod.NONE, InterestMethod.FLAT);

        assertThat(InterestMethod.REDUCING_BALANCE.isAdminSelectable()).isFalse();
    }

    @Test
    void theAuditCoversEveryConfigurableDomainAndDoesNotAuditItself() {
        assertThat(ConfigDomain.values())
                .as("one constant per configurable domain -- seven, for eight tables")
                .hasSize(CONFIGURATION_ENTITIES.size() - 1);

        assertThat(Arrays.stream(ConfigDomain.values()).map(Enum::name).toList())
                .as("the audit table is absent on purpose: it never audits itself, and a "
                        + "CONFIG_AUDIT domain would invite a row explaining a row")
                .doesNotContain("CONFIG_AUDIT", "ORGANIZATION_CONFIG_AUDIT");
    }

    // ------------------------------------------------------------------ exclusions are unordered

    /**
     * Decision: one row per unordered pair, normalized before persistence.
     *
     * <p>The V10 CHECK enforces the same rule in the database, but that test needs Docker and this
     * one does not -- and the CHECK is the wrong place to <em>discover</em> the rule. A caller who
     * built an unnormalized pair would get a constraint violation naming
     * {@code ck_organization_loan_type_exclusion_normalized} at flush time, from a stack that no
     * longer mentions the code that chose the order. So normalization happens at construction, and
     * this is what pins it.
     */
    @Test
    void anExclusionIsOneUnorderedPairWhicheverOrderItIsGivenIn() {
        Organization cooperative = new Organization();

        OrganizationLoanTypeExclusion ascending =
                OrganizationLoanTypeExclusion.between(cooperative, 7L, 42L);
        OrganizationLoanTypeExclusion descending =
                OrganizationLoanTypeExclusion.between(cooperative, 42L, 7L);

        for (OrganizationLoanTypeExclusion exclusion : List.of(ascending, descending)) {
            assertThat(exclusion.getLoanTypeId())
                    .as("the smaller id always lands in loan_type_id, so the two arguments "
                            + "produce the same row rather than two rows that could be edited "
                            + "apart and disagree about which way the rule points")
                    .isEqualTo(7L);
            assertThat(exclusion.getExcludedLoanTypeId()).isEqualTo(42L);
        }
    }

    @Test
    void anExclusionAnswersInBothDirections() {
        // The counterpart of normalization: because the pair is stored once, every read of it has
        // to be symmetric. A member applying for 42 must learn about 7 even though 7 is the column
        // the row is filed under.
        OrganizationLoanTypeExclusion exclusion =
                OrganizationLoanTypeExclusion.between(new Organization(), 42L, 7L);

        assertThat(exclusion.involves(7L)).isTrue();
        assertThat(exclusion.involves(42L)).isTrue();
        assertThat(exclusion.involves(99L)).isFalse();
        assertThat(exclusion.involves(null)).isFalse();

        assertThat(exclusion.counterpartOf(7L)).isEqualTo(42L);
        assertThat(exclusion.counterpartOf(42L)).isEqualTo(7L);
        assertThat(exclusion.counterpartOf(99L))
                .as("a rule that does not concern the type asked about has no answer, and "
                        + "returning either half of the pair would be a wrong one")
                .isNull();
    }

    @Test
    void aLoanTypeCannotExcludeItself() {
        assertThatThrownBy(() ->
                OrganizationLoanTypeExclusion.between(new Organization(), 7L, 7L))
                .as("a type that excluded itself would forbid a second loan of that type -- which "
                        + "is what maxActiveLoans governs. Two mechanisms for one rule are free to "
                        + "disagree later")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exclude itself");
    }

    @Test
    void anExclusionCannotBeBuiltWithoutACooperativeAndTwoTypes() {
        Organization cooperative = new Organization();

        assertThatThrownBy(() -> OrganizationLoanTypeExclusion.between(null, 7L, 42L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrganizationLoanTypeExclusion.between(cooperative, null, 42L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrganizationLoanTypeExclusion.between(cooperative, 7L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thereIsNoWayToBuildAnUnnormalizedExclusion() {
        // The reason the entity's Lombok differs from its siblings: no @Builder, no
        // @AllArgsConstructor, no @Setter. Any of the three would reintroduce a path that skips
        // between() and lands on a CHECK violation at flush instead.
        Class<?> exclusion = OrganizationLoanTypeExclusion.class;

        assertThat(Arrays.stream(exclusion.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .filter(name -> name.startsWith("set") || "builder".equals(name))
                .toList())
                .as("a setter or a builder would let a caller choose the column order, and the "
                        + "database would then refuse the row with a constraint name the caller "
                        + "has never heard of")
                .isEmpty();

        assertThat(Arrays.stream(exclusion.getDeclaredConstructors())
                .filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
                .toList())
                .as("between() must be the only way in; Hibernate uses the protected no-arg "
                        + "constructor")
                .isEmpty();
    }

    // ------------------------------------------------------------------ Class C prohibition

    @ParameterizedTest
    @MethodSource("configurationEntities")
    void noSecurityControlAndNoPenaltyIsRepresentableAsConfiguration(Class<?> entity) {
        for (Field field : entity.getDeclaredFields()) {
            String name = field.getName().toLowerCase();
            for (String forbidden : FORBIDDEN_FIELD_NAME_FRAGMENTS) {
                assertThat(name)
                        .as("%s.%s -- see FORBIDDEN_FIELD_NAME_FRAGMENTS. A security control a "
                                + "tenant administrator can relax is not a setting, and a penalty "
                                + "column with nothing enforcing it is a promise to members that "
                                + "the code does not keep", entity.getSimpleName(), field.getName())
                        .doesNotContain(forbidden);
            }
        }
    }

    @Test
    void theOnboardingFlagsGovernDocumentsRatherThanCredentials() {
        // Negative control for the rule above: `requirePassport` is a field the forbidden-fragment
        // scan must NOT flag, and it would be flagged the moment someone shortened the list to
        // bare "pass". If this assertion ever fails, the scan has become too coarse to trust.
        assertThat(field(OrganizationMembershipConfig.class, "requirePassport")).isNotNull();
        assertThat(FORBIDDEN_FIELD_NAME_FRAGMENTS)
                .allSatisfy(fragment -> assertThat("requirepassport").doesNotContain(fragment));
    }

    // ------------------------------------------------------------------ the audit is immutable

    @Test
    void theAuditHasNoWayToBeChangedFromJava() {
        Class<?> audit = OrganizationConfigAudit.class;

        List<String> setters = Arrays.stream(audit.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .filter(name -> name.startsWith("set"))
                .toList();

        assertThat(setters)
                .as("an audit record with a setter is a record that can be edited after the fact")
                .isEmpty();

        for (Field field : audit.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if ("id".equals(field.getName())) {
                continue; // generated; Hibernate assigns it and no @Column governs it
            }

            boolean updatable = field.isAnnotationPresent(Column.class)
                    ? field.getAnnotation(Column.class).updatable()
                    : field.isAnnotationPresent(JoinColumn.class)
                            && field.getAnnotation(JoinColumn.class).updatable();

            assertThat(updatable)
                    .as("%s must be updatable = false, so a dirty-checked entity in a persistence "
                            + "context cannot rewrite it on flush", field.getName())
                    .isFalse();
        }
    }

    @Test
    void theAuditRecordsWhoChangedWhatFromWhatToWhatWhenAndWhy() {
        // Not decoration: this is the minimum set Decision 4 requires an audit record to preserve,
        // and it is the set the Stage 2 writer will be checked against.
        for (String required : List.of("organization", "actorUserId", "actorLedgerId",
                "configDomain", "settingKey", "oldValue", "newValue",
                "effectiveDate", "reason", "createdAt")) {
            assertThat(field(OrganizationConfigAudit.class, required))
                    .as("the audit must preserve '%s'", required)
                    .isNotNull();
        }
    }

    // ------------------------------------------------------------------ helper

    private static Field field(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError(type.getSimpleName() + " has no field '" + name + "'", e);
        }
    }
}
