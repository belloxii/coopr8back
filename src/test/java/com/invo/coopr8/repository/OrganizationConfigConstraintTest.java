package com.invo.coopr8.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

import com.invo.coopr8.support.AbstractTwoTenantTest;

/**
 * Executes the Phase 4 configuration constraints against a real PostgreSQL, because
 * {@code spring.jpa.hibernate.ddl-auto=validate} does not check any of them.
 *
 * <p>This is the gap that matters. {@code validate} compares tables and column types and stops
 * there: it never looks at a UNIQUE constraint, a CHECK, a composite foreign key or a trigger. So
 * every rule in V3-V8 and V10 that actually protects a cooperative's money -- one configuration row
 * per organization, an interest method the engine can compute, one row per unordered exclusion pair,
 * an actor who belongs to the cooperative whose rate changed, an audit record nobody can rewrite --
 * is invisible to the boot-time check and would be invisible to the whole test suite too.
 * {@code SchemaGenTest} cannot see them either; it only proves the columns are the right shape.
 *
 * <p>So these tests assert by <em>attempting the forbidden write</em> and requiring the database to
 * refuse it. A test that inserted only valid rows would pass identically against a schema with
 * every constraint dropped.
 *
 * <p>Writes go through {@code jdbcTemplate} rather than the repositories on purpose: the point is
 * what the database refuses, not what the application declines to ask. A constraint that only holds
 * while the Java layer is well-behaved is not a constraint. The one repository this class autowires
 * is read-only and is there for a question the schema cannot answer -- see {@link #exclusions}.
 *
 * <p><strong>Requires Docker.</strong> Reported as SKIPPED where no daemon is available -- which is
 * not a pass. {@code DockerRequirementTest} turns the skip into a failure under
 * {@code -Dcoopr8.test.require-docker=true}, which is how CI must run this.
 */
class OrganizationConfigConstraintTest extends AbstractTwoTenantTest {

    /** The eight Phase 4 tables, as the migrations name them. */
    private static final List<String> CONFIGURATION_TABLES = List.of(
            "organization_loan_config",
            "organization_loan_type",
            "organization_loan_type_exclusion",
            "organization_savings_plan",
            "organization_shares_config",
            "organization_repayment_config",
            "organization_membership_config",
            "organization_config_audit");

    /**
     * Autowired for exactly one purpose: the exclusion pair is stored once and therefore has to be
     * READ symmetrically, and symmetry is a property of the query rather than of the schema. Every
     * other assertion in this class goes through {@code jdbcTemplate} on purpose -- see the class
     * Javadoc -- but "does the repository find this row from both directions" cannot be asked of
     * raw SQL that this test wrote itself.
     */
    @Autowired
    private OrganizationLoanTypeExclusionRepository exclusions;

    // ============================================================ cardinality

    @Test
    @DisplayName("A cooperative cannot have two loan configurations")
    void aCooperativeCannotHaveTwoLoanConfigurations() {
        insertLoanConfig(alpha.organizationId(), "FLAT", "12.000");

        assertThatThrownBy(() -> insertLoanConfig(alpha.organizationId(), "NONE", "0.000"))
                .as("two rows would make the applicable interest rate a coin toss, and the bug "
                        + "would surface months later as an intermittent rate")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_organization_loan_config_organization");
    }

    @Test
    @DisplayName("Each singleton configuration is one row per cooperative, and both tenants get one")
    void eachSingletonConfigurationIsOneRowPerCooperative() {
        record Singleton(String table, String columns, String values) {
        }

        List<Singleton> singletons = List.of(
                new Singleton("organization_loan_config",
                        "interest_method, interest_rate, required_guarantors",
                        "'FLAT', 10.000, 2"),
                new Singleton("organization_shares_config",
                        "share_price, approval_required, withdrawal_allowed",
                        "1.00, true, true"),
                new Singleton("organization_repayment_config",
                        "allow_partial_repayment, allow_overpayment, settlement_tolerance",
                        "false, false, 0.00"),
                new Singleton("organization_membership_config",
                        "require_email, require_phone, require_psn, require_passport, "
                                + "require_next_of_kin, auto_activate_members, "
                                + "default_member_status",
                        "true, true, false, false, false, false, 'PENDING'"));

        for (Singleton singleton : singletons) {
            // Both cooperatives may hold one row each -- the constraint is per organization, not
            // global. A UNIQUE on the wrong column list would fail here rather than below.
            insertSingleton(singleton.table(), singleton.columns(), singleton.values(),
                    alpha.organizationId());
            insertSingleton(singleton.table(), singleton.columns(), singleton.values(),
                    beta.organizationId());

            assertThatThrownBy(() -> insertSingleton(singleton.table(), singleton.columns(),
                    singleton.values(), alpha.organizationId()))
                    .as("%s must hold at most one row per cooperative", singleton.table())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    @DisplayName("Loan-type names are unique within a cooperative, ignoring case and padding, and "
            + "free across cooperatives")
    void loanTypeNamesAreUniqueWithinACooperativeOnly() {
        insertLoanType(alpha.organizationId(), "Soft Loan");

        assertThatThrownBy(() -> insertLoanType(alpha.organizationId(), "  soft   loan  "))
                .as("two products a member cannot tell apart are an operational hazard; the "
                        + "unique index is on lower(btrim(name))")
                .isInstanceOf(DataIntegrityViolationException.class);

        // But "Soft Loan" is a name most cooperatives use. Uniqueness that spanned tenants would
        // let the first cooperative to register a product name deny it to every other one.
        assertThatCode(() -> insertLoanType(beta.organizationId(), "Soft Loan"))
                .doesNotThrowAnyException();

        // Interior spacing is not normalized away, only leading/trailing padding and case. Two
        // genuinely different names stay different.
        assertThatCode(() -> insertLoanType(alpha.organizationId(), "Soft Loan Extended"))
                .doesNotThrowAnyException();
    }

    // ============================================================ CHECK constraints

    @Test
    @DisplayName("An interest method the engine cannot compute is refused by the database")
    void anUnknownInterestMethodIsRefused() {
        assertThatThrownBy(() -> insertLoanConfig(alpha.organizationId(), "DAILY_COMPOUND", "5.000"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_organization_loan_config_interest_method");

        // All three enum constants are accepted, including REDUCING_BALANCE: the schema admits it
        // so it need not be redesigned later. Keeping it out of the admin UI is the application's
        // job -- InterestMethod.adminSelectable -- not the constraint's.
        for (String method : List.of("NONE", "FLAT", "REDUCING_BALANCE")) {
            jdbcTemplate.update("DELETE FROM organization_loan_config WHERE organization_id = ?",
                    alpha.organizationId());
            assertThatCode(() -> insertLoanConfig(alpha.organizationId(), method, "1.000"))
                    .as("%s is a persisted constant and the CHECK must admit it", method)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("A negative interest rate is refused")
    void aNegativeInterestRateIsRefused() {
        assertThatThrownBy(() -> insertLoanConfig(alpha.organizationId(), "FLAT", "-0.001"))
                .as("a negative rate would pay members to borrow")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_organization_loan_config_interest_rate");

        assertThatCode(() -> insertLoanConfig(alpha.organizationId(), "NONE", "0.000"))
                .as("zero is the interest-free case and must be representable")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A third required guarantor is refused, because Loan has only two columns for one")
    void aThirdRequiredGuarantorIsRefused() {
        assertThatThrownBy(() -> insertLoanConfig(alpha.organizationId(), "FLAT", "10.000", 3))
                .as("a setting that displays 3 while the schema silently caps at 2 is a lie told "
                        + "by the schema")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_organization_loan_config_required_guarantors");

        for (int guarantors : new int[] {0, 1, 2}) {
            jdbcTemplate.update("DELETE FROM organization_loan_config WHERE organization_id = ?",
                    alpha.organizationId());
            assertThatCode(() -> insertLoanConfig(alpha.organizationId(), "FLAT", "10.000",
                    guarantors))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("An inverted amount window is refused")
    void anInvertedAmountWindowIsRefused() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO organization_loan_config
                    (organization_id, interest_method, interest_rate, min_loan_amount,
                     max_loan_amount, required_guarantors, created_at)
                VALUES (?, 'FLAT', 10.000, 500000.00, 50000.00, 2, now())
                """, alpha.organizationId()))
                .as("a minimum above the maximum admits no loan at all, so every member of that "
                        + "cooperative would be silently unable to borrow")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_organization_loan_config_amount_window");
    }

    @Test
    @DisplayName("A share price of zero is refused")
    void aSharePriceOfZeroIsRefused() {
        assertThatThrownBy(() -> insertSingleton("organization_shares_config",
                "share_price, approval_required, withdrawal_allowed",
                "0.00, true, true", alpha.organizationId()))
                .as("units purchased = amount / share_price, so zero is a division by zero in the "
                        + "middle of a member's money")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_organization_shares_config_share_price");
    }

    @Test
    @DisplayName("A settlement tolerance above one naira is refused")
    void aSettlementToleranceAboveOneNairaIsRefused() {
        assertThatThrownBy(() -> insertSingleton("organization_repayment_config",
                "allow_partial_repayment, allow_overpayment, settlement_tolerance",
                "false, false, 5000.00", alpha.organizationId()))
                .as("a tolerance larger than rounding is not rounding, it is forgiving debt -- "
                        + "which is a deliberate transaction with an audit trail, not a setting")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_organization_repayment_config_tolerance");

        assertThatCode(() -> insertSingleton("organization_repayment_config",
                "allow_partial_repayment, allow_overpayment, settlement_tolerance",
                "false, false, 1.00", alpha.organizationId()))
                .as("one naira is the documented ceiling and must be inclusive")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Auto-activation and the default member status cannot contradict each other")
    void autoActivationAndDefaultStatusCannotContradictEachOther() {
        String columns = "require_email, require_phone, require_psn, require_passport, "
                + "require_next_of_kin, auto_activate_members, default_member_status";

        assertThatThrownBy(() -> insertSingleton("organization_membership_config", columns,
                "true, true, false, false, false, true, 'PENDING'", alpha.organizationId()))
                .as("auto-activation that lands a member in PENDING activates nobody")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_organization_membership_config_activation");

        assertThatThrownBy(() -> insertSingleton("organization_membership_config", columns,
                "true, true, false, false, false, false, 'ACTIVE'", alpha.organizationId()))
                .as("a default of ACTIVE without auto-activation bypasses the approval step the "
                        + "cooperative said it wanted")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_organization_membership_config_activation");

        assertThatThrownBy(() -> insertSingleton("organization_membership_config", columns,
                "true, true, false, false, false, false, 'AWAITING_REVIEW'",
                alpha.organizationId()))
                .as("a free-form status would create members no code path can ever unlock")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_organization_membership_config_status");

        assertThatCode(() -> insertSingleton("organization_membership_config", columns,
                "true, true, false, false, false, false, 'PENDING'", alpha.organizationId()))
                .as("PENDING without auto-activation is today's behaviour and the seeded default")
                .doesNotThrowAnyException();
    }

    // ============================================================ the audit

    @Test
    @DisplayName("An administrator cannot be recorded as the actor on another cooperative's change")
    void theAuditActorMustBelongToTheCooperativeWhoseConfigurationChanged() {
        assertThatThrownBy(() -> insertAudit(alpha.organizationId(), beta.admin().id(),
                "LOAN_CONFIG", "interestRate", "0.000", "12.000"))
                .as("the composite FK on (organization_id, actor_user_id) is what makes "
                        + "'Beta's admin changed Alpha's rate' a row PostgreSQL refuses")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_organization_config_audit_actor");

        assertThatCode(() -> insertAudit(alpha.organizationId(), alpha.admin().id(),
                "LOAN_CONFIG", "interestRate", "0.000", "12.000"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("An audit record cannot be updated or deleted")
    void anAuditRecordCannotBeUpdatedOrDeleted() {
        insertAudit(alpha.organizationId(), alpha.admin().id(),
                "LOAN_CONFIG", "interestRate", "0.000", "12.000");
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM organization_config_audit WHERE organization_id = ?",
                Long.class, alpha.organizationId());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE organization_config_audit SET new_value = '1.000' WHERE id = ?", id))
                .as("correcting a mistaken change is a corrective change with its own audit row, "
                        + "never an edit to the record of what happened")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM organization_config_audit WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThat(countWhere("organization_config_audit WHERE id = ?", id))
                .as("the row must still be there after both attempts")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("An audit record that records no change is refused")
    void anAuditRecordThatRecordsNoChangeIsRefused() {
        assertThatThrownBy(() -> insertAudit(alpha.organizationId(), alpha.admin().id(),
                "LOAN_CONFIG", "interestRate", "12.000", "12.000"))
                .as("a row saying the rate changed from 12% to 12% is noise in the one place "
                        + "that must stay readable")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_organization_config_audit_change");

        // A first-ever set has no old value, and clearing a bound has no new one. Both are real
        // changes and both must be recordable -- which is why only equality is forbidden.
        assertThatCode(() -> insertAudit(alpha.organizationId(), alpha.admin().id(),
                "LOAN_CONFIG", "minLoanAmount", null, "50000.00"))
                .doesNotThrowAnyException();
        assertThatCode(() -> insertAudit(alpha.organizationId(), alpha.admin().id(),
                "LOAN_CONFIG", "maxLoanAmount", "500000.00", null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The audit domain is restricted to the seven configurable domains")
    void theAuditDomainIsRestrictedToTheSevenConfigurableDomains() {
        assertThatThrownBy(() -> insertAudit(alpha.organizationId(), alpha.admin().id(),
                "CONFIG_AUDIT", "somethingKey", null, "value"))
                .as("the audit never audits itself")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_organization_config_audit_domain");

        // Positive control for the V10 addition: LOAN_TYPE_EXCLUSION is a domain of its own, and a
        // CHECK that had not been updated alongside ConfigDomain would reject it here. `validate`
        // does not inspect CHECK constraints, so this is the only place the two can be compared.
        assertThatCode(() -> insertAudit(alpha.organizationId(), alpha.admin().id(),
                "LOAN_TYPE_EXCLUSION", "excludedLoanTypeId", null, "42"))
                .doesNotThrowAnyException();
    }

    // ============================================================ loan-type exclusions

    @Test
    @DisplayName("A cooperative can declare two of its own loan types mutually exclusive")
    void aCooperativeCanDeclareTwoOfItsOwnLoanTypesMutuallyExclusive() {
        long real = insertLoanTypeReturningId(alpha.organizationId(), "Real Estate Loan");
        long material = insertLoanTypeReturningId(alpha.organizationId(), "Material Loan");

        assertThatCode(() -> insertExclusion(alpha.organizationId(), real, material))
                .doesNotThrowAnyException();

        assertThat(countWhere("organization_loan_type_exclusion WHERE organization_id = ?",
                alpha.organizationId()))
                .as("one rule, one row -- the pair is unordered and stored once")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("The exclusion resolves from either direction, because it is stored once")
    void theExclusionResolvesFromEitherDirection() {
        long real = insertLoanTypeReturningId(alpha.organizationId(), "Real Estate Loan");
        long material = insertLoanTypeReturningId(alpha.organizationId(), "Material Loan");
        long soft = insertLoanTypeReturningId(alpha.organizationId(), "Soft Loan");
        insertExclusion(alpha.organizationId(), real, material);

        // The requirement, stated as the two calls that must agree. Storing the pair once is only
        // safe if every read looks at both columns; a read that checked loan_type_id alone would
        // answer true for one of these and false for the other, and the rule would apply in one
        // direction only.
        assertThat(exclusions.existsBetween(alpha.organizationId(), real, material)).isTrue();
        assertThat(exclusions.existsBetween(alpha.organizationId(), material, real)).isTrue();

        assertThat(exclusions.existsBetween(alpha.organizationId(), real, soft))
                .as("a pair nobody declared must not be excluded")
                .isFalse();
        assertThat(exclusions.existsBetween(beta.organizationId(), real, material))
                .as("Beta did not configure this rule and does not own these types; the composite "
                        + "FK makes the row unreachable and the tenant predicate makes it invisible")
                .isFalse();

        // The other symmetric read: "the member wants Material -- what is Material incompatible
        // with?" The row is filed under whichever id is smaller, so this must not depend on which.
        assertThat(exclusions.findAllInvolvingLoanType(alpha.organizationId(), material))
                .singleElement()
                .satisfies(rule -> assertThat(rule.counterpartOf(material)).isEqualTo(real));
        assertThat(exclusions.findAllInvolvingLoanType(alpha.organizationId(), real))
                .singleElement()
                .satisfies(rule -> assertThat(rule.counterpartOf(real)).isEqualTo(material));
        assertThat(exclusions.findAllInvolvingLoanType(alpha.organizationId(), soft))
                .as("Soft is in no rule")
                .isEmpty();
    }

    @Test
    @DisplayName("The same pair cannot be declared twice in the order it is stored")
    void theSamePairCannotBeDeclaredTwiceInTheStoredOrder() {
        long real = insertLoanTypeReturningId(alpha.organizationId(), "Real Estate Loan");
        long material = insertLoanTypeReturningId(alpha.organizationId(), "Material Loan");
        insertExclusion(alpha.organizationId(), real, material);

        assertThatThrownBy(() -> insertExclusion(alpha.organizationId(), real, material))
                .as("two rows for one rule could be edited apart, leaving an exclusion that "
                        + "applies in one direction only")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_organization_loan_type_exclusion_pair");
    }

    @Test
    @DisplayName("The same pair cannot be declared twice in the reverse order either")
    void theSamePairCannotBeDeclaredTwiceInTheReverseOrder() {
        long real = insertLoanTypeReturningId(alpha.organizationId(), "Real Estate Loan");
        long material = insertLoanTypeReturningId(alpha.organizationId(), "Material Loan");
        insertExclusion(alpha.organizationId(), real, material);

        // This is the case a plain UNIQUE (loan_type_id, excluded_loan_type_id) would NOT catch,
        // and it is the whole reason the normalization CHECK exists: reversed, the pair is a
        // different ordered tuple and the UNIQUE would happily accept it as a second row.
        long lower = Math.min(real, material);
        long higher = Math.max(real, material);

        assertThatThrownBy(() -> insertExclusionUnnormalized(alpha.organizationId(), higher, lower))
                .as("the reversed pair never reaches the UNIQUE at all -- the CHECK refuses it "
                        + "first, which is what makes one row per unordered pair a database "
                        + "guarantee rather than a service convention")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_organization_loan_type_exclusion_normalized");

        // And normalizing the reversed pair -- which is what the entity's between() does -- makes
        // it collide with the row that is already there.
        assertThatThrownBy(() -> insertExclusion(alpha.organizationId(), material, real))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_organization_loan_type_exclusion_pair");

        assertThat(countWhere("organization_loan_type_exclusion WHERE organization_id = ?",
                alpha.organizationId()))
                .as("after both attempts there must still be exactly one row")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("A loan type cannot exclude itself")
    void aLoanTypeCannotExcludeItself() {
        long real = insertLoanTypeReturningId(alpha.organizationId(), "Real Estate Loan");

        assertThatThrownBy(() -> insertExclusionUnnormalized(alpha.organizationId(), real, real))
                .as("a type excluding itself would forbid a second loan of that type, which is "
                        + "what organization_loan_type.max_active_loans governs -- and the strict "
                        + "< in the normalization CHECK refuses a = a without a second constraint")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_organization_loan_type_exclusion_normalized");
    }

    @Test
    @DisplayName("A cooperative cannot exclude another cooperative's loan type")
    void aCooperativeCannotExcludeAnotherCooperativesLoanType() {
        long alphaType = insertLoanTypeReturningId(alpha.organizationId(), "Alpha Product");
        long betaType = insertLoanTypeReturningId(beta.organizationId(), "Beta Product");

        // Both composite FKs are exercised, because the pair is normalized and which of the two
        // columns Beta's id lands in depends on which id happens to be smaller. Whichever way
        // round it is, the (organization_id, loan_type_id) pair does not exist in
        // organization_loan_type and PostgreSQL refuses the row.
        assertThatThrownBy(() -> insertExclusion(alpha.organizationId(), alphaType, betaType))
                .as("a cooperative that could name another's product in its own rule could infer "
                        + "which ids exist on the platform, and would be configuring a rule about "
                        + "a product its members cannot see")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_organization_loan_type_exclusion_");

        assertThat(countWhere("organization_loan_type_exclusion WHERE organization_id = ?",
                alpha.organizationId()))
                .isZero();

        // The mirror image: Beta cannot reach into Alpha either. Not symmetry for its own sake --
        // an isolation check that only ran one way could pass because of an accident of which
        // organization was seeded first.
        assertThatThrownBy(() -> insertExclusion(beta.organizationId(), betaType, alphaType))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_organization_loan_type_exclusion_");
    }

    // ============================================================ tenant isolation

    @Test
    @DisplayName("A scoped read of one cooperative's configuration never returns another's")
    void aScopedReadNeverReturnsAnotherCooperativesConfiguration() {
        insertLoanConfig(alpha.organizationId(), "FLAT", "12.000");
        insertLoanConfig(beta.organizationId(), "NONE", "0.000");
        insertLoanType(alpha.organizationId(), "Alpha Product");
        insertLoanType(beta.organizationId(), "Beta Product");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT interest_rate::text FROM organization_loan_config WHERE organization_id = ?
                """, String.class, alpha.organizationId()))
                .isEqualTo("12.000");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT interest_rate::text FROM organization_loan_config WHERE organization_id = ?
                """, String.class, beta.organizationId()))
                .as("Alpha's rate change must not have moved Beta's rate")
                .isEqualTo("0.000");

        assertThat(jdbcTemplate.queryForList("""
                SELECT name FROM organization_loan_type WHERE organization_id = ? ORDER BY name
                """, String.class, alpha.organizationId()))
                .containsExactly("Alpha Product");
    }

    @Test
    @DisplayName("No configuration table carries a security control or a penalty column")
    void noConfigurationTableCarriesASecurityControlOrPenaltyColumn() {
        // The Class C prohibition, checked at the table rather than the entity. This is the
        // stronger form: `ddl-auto=validate` ignores columns no entity maps, and the reflection
        // test in OrganizationConfigurationModelTest can only see fields. A migration that added
        // `password_regex` with no Java field would pass both and fail here.
        String placeholders = String.join(", ",
                CONFIGURATION_TABLES.stream().map(table -> "?").toList());
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT table_name || '.' || column_name "
                        + "FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() "
                        + "  AND table_name IN (" + placeholders + ")",
                String.class, CONFIGURATION_TABLES.toArray());

        assertThat(columns)
                .as("the eight configuration tables must be present, or this test read nothing")
                .isNotEmpty();

        for (String qualified : columns) {
            String name = qualified.toLowerCase();
            assertThat(name)
                    .as("%s -- password rules, token lifetimes and signing keys are platform "
                            + "constants; a tenant administrator who could relax one would be "
                            + "relaxing it for their own members. And a penalty column with "
                            + "nothing enforcing it is a promise the code does not keep "
                            + "[decision 6]", qualified)
                    .doesNotContain("password", "passwd", "secret", "credential",
                            "jwt", "regex", "penalt", "latefee", "late_fee", "late_charge");
        }

        // Negative control: require_passport is a document upload, not a credential, and must
        // still be there. If the scan above ever broadened to bare "pass" this would catch it.
        assertThat(columns).contains("organization_membership_config.require_passport");
    }

    @Test
    @DisplayName("Truncation between tests leaves no configuration behind")
    void truncationBetweenTestsLeavesNoConfigurationBehind() {
        // Documents a real consequence rather than testing the schema: V9 seeded one row per
        // organization at migration time, and AbstractIntegrationTest truncates. So a cooperative
        // seeded by a test has NO configuration -- the same state a cooperative onboarded after
        // Stage 1 is in. Any test that needs configuration must insert it, and Stage 2's
        // provisioning path is what must guarantee it in production.
        for (String table : CONFIGURATION_TABLES) {
            assertThat(rowCount(table))
                    .as("%s must start empty, so a test that needs configuration creates it "
                            + "explicitly rather than inheriting a migration artefact", table)
                    .isZero();
        }
    }

    // ============================================================ helpers

    private void insertLoanConfig(long organizationId, String method, String rate) {
        insertLoanConfig(organizationId, method, rate, 2);
    }

    private void insertLoanConfig(long organizationId, String method, String rate,
            int requiredGuarantors) {
        jdbcTemplate.update("""
                INSERT INTO organization_loan_config
                    (organization_id, interest_method, interest_rate, required_guarantors,
                     created_at)
                VALUES (?, ?, CAST(? AS numeric(6,3)), ?, now())
                """, organizationId, method, rate, requiredGuarantors);
    }

    private void insertLoanType(long organizationId, String name) {
        jdbcTemplate.update("""
                INSERT INTO organization_loan_type
                    (organization_id, name, max_active_loans, active, created_at)
                VALUES (?, ?, 1, true, now())
                """, organizationId, name);
    }

    private long insertLoanTypeReturningId(long organizationId, String name) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO organization_loan_type
                    (organization_id, name, max_active_loans, active, created_at)
                VALUES (?, ?, 1, true, now())
                RETURNING id
                """, Long.class, organizationId, name);
        return id == null ? 0L : id;
    }

    /**
     * Inserts an exclusion the way the entity's {@code between()} factory does: normalized, smaller
     * id first, whichever order the caller passed them in.
     */
    private void insertExclusion(long organizationId, long firstLoanTypeId, long secondLoanTypeId) {
        insertExclusionUnnormalized(organizationId,
                Math.min(firstLoanTypeId, secondLoanTypeId),
                Math.max(firstLoanTypeId, secondLoanTypeId));
    }

    /**
     * Inserts the columns exactly as given, normalized or not. Needed because the reversed-pair and
     * self-exclusion cases only exist <em>before</em> normalization: an insert that normalizes
     * first can never produce the row those two constraints are there to refuse.
     */
    private void insertExclusionUnnormalized(long organizationId, long loanTypeId,
            long excludedLoanTypeId) {
        jdbcTemplate.update("""
                INSERT INTO organization_loan_type_exclusion
                    (organization_id, loan_type_id, excluded_loan_type_id, created_at)
                VALUES (?, ?, ?, now())
                """, organizationId, loanTypeId, excludedLoanTypeId);
    }

    private void insertSingleton(String table, String columns, String values, long organizationId) {
        jdbcTemplate.update("INSERT INTO " + table + " (organization_id, " + columns
                + ", created_at) VALUES (" + organizationId + ", " + values + ", now())");
    }

    private void insertAudit(long organizationId, long actorUserId, String domain,
            String settingKey, String oldValue, String newValue) {
        jdbcTemplate.update("""
                INSERT INTO organization_config_audit
                    (organization_id, actor_user_id, actor_ledger_id, config_domain, setting_key,
                     old_value, new_value, effective_date, reason, created_at)
                VALUES (?, ?, ?, ?, ?, CAST(? AS text), CAST(? AS text), current_date, ?, now())
                """, organizationId, actorUserId, "ACTOR-" + actorUserId, domain, settingKey,
                oldValue, newValue, "constraint test");
    }

    /**
     * Named {@code rowCount} rather than overloading the inherited
     * {@code countWhere(String, Object...)}: two same-named helpers where one takes varargs and
     * the other does not is a resolution puzzle at every call site.
     */
    private long rowCount(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }
}
