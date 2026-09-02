package com.invo.coopr8.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.invo.coopr8.model.Organization;
import com.invo.coopr8.repository.OrganizationRepository;
import com.invo.coopr8.support.AbstractTwoTenantTest;

/**
 * Provisioning a cooperative's configuration, against a real PostgreSQL container.
 *
 * <p>{@link ConfigProvisioner}'s own comment names this class: the defaults live in two places, the
 * provisioner and {@code V9__phase4_seed_defaults.sql}, and two places holding the same numbers is a
 * place for them to drift. {@code ConfigDefaultsMatchMigrationTest} already compares the constants
 * against the migration <em>text</em> with no database at all. This class is the other half, and the
 * half that cannot be faked by reading source: it provisions cooperatives that really exist in a real
 * schema and reads the rows back out with raw SQL.
 *
 * <p>Every test here starts from a cooperative with <strong>no configuration rows whatsoever</strong>
 * — {@code AbstractIntegrationTest} truncates the tenant tables, which removes what V9 seeded, so
 * Alpha and Beta are in exactly the state of a cooperative onboarded after this feature shipped. That
 * is the state provisioning exists for, and the state a migration cannot cover.
 *
 * <h2>Why the direct calls run unfiltered, and why that is the point</h2>
 * {@link com.invo.coopr8.tenant.TenantAwareJpaTransactionManager} enables the Hibernate tenant
 * {@code @Filter} only when a tenant is bound to the thread, and the base class clears
 * {@code TenantContext} before each test. So when this class calls the provisioner directly there is
 * no ambient tenant and no filter: what keeps Alpha's provisioning out of Beta's rows is
 * {@code findByOrganizationId}'s explicit predicate and nothing else. If that predicate were ever
 * dropped in the belief that the filter would cover it, these tests fail.
 */
class ConfigProvisioningTest extends AbstractTwoTenantTest {

    @Autowired
    private ConfigProvisioner provisioner;

    @Autowired
    private OrganizationRepository organizationRepository;

    // ------------------------------------------------------------------ vacuity guard

    @Test
    @DisplayName("A cooperative onboarded after V9 arrives with no configuration at all")
    void aFreshCooperativeHasNoConfigurationRows() {
        // If this ever fails, every assertion below is measuring a migration's seed rows rather than
        // the provisioner, and the class proves nothing.
        for (long organizationId : new long[] { alpha.organizationId(), beta.organizationId() }) {
            assertThat(rowCount("organization_loan_config", organizationId)).isZero();
            assertThat(rowCount("organization_shares_config", organizationId)).isZero();
            assertThat(rowCount("organization_repayment_config", organizationId)).isZero();
            assertThat(rowCount("organization_membership_config", organizationId)).isZero();
            assertThat(rowCount("organization_loan_type", organizationId)).isZero();
            assertThat(rowCount("organization_savings_plan", organizationId)).isZero();
            assertThat(rowCount("organization_loan_type_exclusion", organizationId)).isZero();
            assertThat(rowCount("organization_config_audit", organizationId)).isZero();
        }
    }

    // --------------------------------------------------------------- the defaults land

    @Test
    @DisplayName("Provisioning writes the loan defaults the constants declare")
    void provisioningWritesTheLoanDefaults() {
        provisioner.provisionAll(organization(alpha.organizationId()));

        Map<String, Object> row = onlyRow("organization_loan_config", alpha.organizationId());
        assertThat(row.get("interest_method"))
                .isEqualTo(ConfigProvisioner.DEFAULT_INTEREST_METHOD.name());
        assertThat(amount(row, "interest_rate"))
                .isEqualByComparingTo(ConfigProvisioner.DEFAULT_INTEREST_RATE);
        assertThat(number(row, "required_guarantors"))
                .isEqualTo(ConfigProvisioner.DEFAULT_REQUIRED_GUARANTORS);

        // No bounds by default: a cooperative that has never opened the settings screen must not
        // find its members refused a loan amount the platform used to accept.
        assertThat(row.get("min_loan_amount")).isNull();
        assertThat(row.get("max_loan_amount")).isNull();
        assertThat(row.get("min_tenure_months")).isNull();
        assertThat(row.get("max_tenure_months")).isNull();
    }

    @Test
    @DisplayName("Provisioning writes the shares defaults the constants declare")
    void provisioningWritesTheSharesDefaults() {
        provisioner.provisionAll(organization(alpha.organizationId()));

        Map<String, Object> row = onlyRow("organization_shares_config", alpha.organizationId());
        assertThat(amount(row, "share_price"))
                .isEqualByComparingTo(ConfigProvisioner.DEFAULT_SHARE_PRICE);
        assertThat(row.get("approval_required"))
                .isEqualTo(ConfigProvisioner.DEFAULT_APPROVAL_REQUIRED);
        assertThat(row.get("withdrawal_allowed"))
                .isEqualTo(ConfigProvisioner.DEFAULT_WITHDRAWAL_ALLOWED);
        assertThat(row.get("min_purchase_amount")).isNull();
        assertThat(row.get("max_purchase_amount")).isNull();
        assertThat(row.get("min_withdrawal_amount")).isNull();
    }

    @Test
    @DisplayName("Provisioning writes the repayment defaults the constants declare")
    void provisioningWritesTheRepaymentDefaults() {
        provisioner.provisionAll(organization(alpha.organizationId()));

        Map<String, Object> row = onlyRow("organization_repayment_config", alpha.organizationId());
        assertThat(row.get("allow_partial_repayment"))
                .isEqualTo(ConfigProvisioner.DEFAULT_ALLOW_PARTIAL_REPAYMENT);
        assertThat(row.get("allow_overpayment"))
                .isEqualTo(ConfigProvisioner.DEFAULT_ALLOW_OVERPAYMENT);
        assertThat(amount(row, "settlement_tolerance"))
                .isEqualByComparingTo(ConfigProvisioner.DEFAULT_SETTLEMENT_TOLERANCE);
    }

    @Test
    @DisplayName("Provisioning writes the membership defaults the constants declare")
    void provisioningWritesTheMembershipDefaults() {
        provisioner.provisionAll(organization(alpha.organizationId()));

        Map<String, Object> row = onlyRow("organization_membership_config", alpha.organizationId());
        assertThat(row.get("require_email")).isEqualTo(ConfigProvisioner.DEFAULT_REQUIRE_EMAIL);
        assertThat(row.get("require_phone")).isEqualTo(ConfigProvisioner.DEFAULT_REQUIRE_PHONE);
        assertThat(row.get("require_psn")).isEqualTo(ConfigProvisioner.DEFAULT_REQUIRE_PSN);
        assertThat(row.get("require_passport"))
                .isEqualTo(ConfigProvisioner.DEFAULT_REQUIRE_PASSPORT);
        assertThat(row.get("require_next_of_kin"))
                .isEqualTo(ConfigProvisioner.DEFAULT_REQUIRE_NEXT_OF_KIN);
        assertThat(row.get("auto_activate_members"))
                .isEqualTo(ConfigProvisioner.DEFAULT_AUTO_ACTIVATE_MEMBERS);
        assertThat(row.get("default_member_status"))
                .isEqualTo(ConfigProvisioner.DEFAULT_MEMBER_STATUS);
    }

    // -------------------------------------------------------------------- idempotence

    @Test
    @DisplayName("Provisioning a cooperative twice changes nothing and reuses the same rows")
    void provisioningIsIdempotent() {
        Organization organization = organization(alpha.organizationId());
        provisioner.provisionAll(organization);

        Map<String, Long> firstIds = Map.of(
                "organization_loan_config", idOf("organization_loan_config", alpha.organizationId()),
                "organization_shares_config",
                idOf("organization_shares_config", alpha.organizationId()),
                "organization_repayment_config",
                idOf("organization_repayment_config", alpha.organizationId()),
                "organization_membership_config",
                idOf("organization_membership_config", alpha.organizationId()));

        provisioner.provisionAll(organization);

        // Not just "still one row": the same row. A second insert would violate the one-row-per
        // cooperative unique index anyway, so a new id here would mean the first row was replaced --
        // and replacing a configuration row silently discards whatever an administrator had set.
        firstIds.forEach((table, id) -> {
            assertThat(rowCount(table, alpha.organizationId()))
                    .as("%s must still hold exactly one row", table)
                    .isEqualTo(1);
            assertThat(idOf(table, alpha.organizationId()))
                    .as("%s must be the same row, not a replacement", table)
                    .isEqualTo(id);
        });
    }

    @Test
    @DisplayName("Provisioning never overwrites a value an administrator chose")
    void provisioningDoesNotOverwriteAdministeredValues() throws Exception {
        // Set a rate through the real endpoint, then provision again -- which is what the next read
        // of any settings screen does.
        as(alphaAdminToken, put("/api/admin/config/loan"), Map.of(
                "interestMethod", "FLAT",
                "interestRate", "7.500",
                "requiredGuarantors", 1,
                "reason", "Board resolution 2026/03"))
                .andExpect(status().isOk());

        provisioner.provisionAll(organization(alpha.organizationId()));

        Map<String, Object> row = onlyRow("organization_loan_config", alpha.organizationId());
        assertThat(row.get("interest_method")).isEqualTo("FLAT");
        assertThat(amount(row, "interest_rate")).isEqualByComparingTo(new BigDecimal("7.500"));
        assertThat(number(row, "required_guarantors")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ what it must NOT do

    @Test
    @DisplayName("Provisioning writes no audit record, because a default nobody chose has no actor")
    void provisioningWritesNoAuditRecord() {
        provisioner.provisionAll(organization(alpha.organizationId()));

        // organization_config_audit.actor_user_id is NOT NULL, and there is no honest value for it
        // here: nobody made this decision. The trail records changes to the defaults, starting from
        // them. It is also why the provisioner can run outside any security context at all.
        assertThat(rowCount("organization_config_audit", alpha.organizationId())).isZero();
    }

    @Test
    @DisplayName("Provisioning invents no loan product and no savings plan")
    void provisioningCreatesNoProductsOrPlans() {
        provisioner.provisionAll(organization(alpha.organizationId()));

        // A product or a plan is a commercial offer with a name and a price. The platform must not
        // invent one on a cooperative's behalf and have it appear to members as something the
        // cooperative offers.
        assertThat(rowCount("organization_loan_type", alpha.organizationId())).isZero();
        assertThat(rowCount("organization_savings_plan", alpha.organizationId())).isZero();
        assertThat(rowCount("organization_loan_type_exclusion", alpha.organizationId())).isZero();
    }

    @Test
    @DisplayName("Provisioning one cooperative leaves the other unprovisioned")
    void provisioningIsScopedToOneCooperative() {
        provisioner.provisionAll(organization(alpha.organizationId()));

        assertThat(rowCount("organization_loan_config", beta.organizationId())).isZero();
        assertThat(rowCount("organization_shares_config", beta.organizationId())).isZero();
        assertThat(rowCount("organization_repayment_config", beta.organizationId())).isZero();
        assertThat(rowCount("organization_membership_config", beta.organizationId())).isZero();

        // And the rows that were written belong to Alpha, rather than to whichever cooperative the
        // ambient session happened to be looking at -- there is no ambient session here.
        assertThat(countWhere("organization_loan_config WHERE organization_id = ?",
                alpha.organizationId())).isEqualTo(1);
    }

    @Test
    @DisplayName("Provisioning both cooperatives gives each its own independent row")
    void bothCooperativesGetTheirOwnRows() {
        provisioner.provisionAll(organization(alpha.organizationId()));
        provisioner.provisionAll(organization(beta.organizationId()));

        long alphaId = idOf("organization_loan_config", alpha.organizationId());
        long betaId = idOf("organization_loan_config", beta.organizationId());
        assertThat(alphaId).isNotEqualTo(betaId);
        assertThat(countWhere("organization_loan_config")).isEqualTo(2);
    }

    // ------------------------------------------------- provisioning through the HTTP surface

    @Test
    @DisplayName("The first read of a settings screen provisions that cooperative, and only it")
    void theFirstReadProvisions() throws Exception {
        JsonNode body = read(as(alphaAdminToken, get("/api/admin/config/loan"))
                .andExpect(status().isOk()));

        assertThat(body.path("interestMethod").asText())
                .isEqualTo(ConfigProvisioner.DEFAULT_INTEREST_METHOD.name());
        assertThat(body.path("interestRate").decimalValue())
                .isEqualByComparingTo(ConfigProvisioner.DEFAULT_INTEREST_RATE);
        assertThat(body.path("requiredGuarantors").asInt())
                .isEqualTo(ConfigProvisioner.DEFAULT_REQUIRED_GUARANTORS);
        assertThat(body.path("id").isNumber()).isTrue();

        assertThat(rowCount("organization_loan_config", alpha.organizationId())).isEqualTo(1);
        assertThat(rowCount("organization_loan_config", beta.organizationId())).isZero();
    }

    @Test
    @DisplayName("A second read of a settings screen creates no second row")
    void theSecondReadProvisionsNothingFurther() throws Exception {
        long firstId = read(as(alphaAdminToken, get("/api/admin/config/shares"))
                .andExpect(status().isOk())).path("id").asLong();
        long secondId = read(as(alphaAdminToken, get("/api/admin/config/shares"))
                .andExpect(status().isOk())).path("id").asLong();

        assertThat(secondId).isEqualTo(firstId);
        assertThat(rowCount("organization_shares_config", alpha.organizationId())).isEqualTo(1);
    }

    @Test
    @DisplayName("Each cooperative's administrator provisions only their own cooperative")
    void eachAdministratorProvisionsOnlyTheirOwn() throws Exception {
        as(alphaAdminToken, get("/api/admin/config/membership")).andExpect(status().isOk());
        assertThat(rowCount("organization_membership_config", beta.organizationId())).isZero();

        as(betaAdminToken, get("/api/admin/config/membership")).andExpect(status().isOk());

        assertThat(rowCount("organization_membership_config", alpha.organizationId())).isEqualTo(1);
        assertThat(rowCount("organization_membership_config", beta.organizationId())).isEqualTo(1);
        assertThat(idOf("organization_membership_config", alpha.organizationId()))
                .isNotEqualTo(idOf("organization_membership_config", beta.organizationId()));
    }

    @Test
    @DisplayName("Reading a settings screen provisions no audit record either")
    void readingASettingsScreenWritesNoAuditRecord() throws Exception {
        as(alphaAdminToken, get("/api/admin/config/loan")).andExpect(status().isOk());
        as(alphaAdminToken, get("/api/admin/config/shares")).andExpect(status().isOk());
        as(alphaAdminToken, get("/api/admin/config/repayment")).andExpect(status().isOk());
        as(alphaAdminToken, get("/api/admin/config/membership")).andExpect(status().isOk());

        assertThat(countWhere("organization_config_audit")).isZero();
    }

    // ------------------------------------------------------------------------- helpers

    private Organization organization(long organizationId) {
        return organizationRepository.findById(organizationId).orElseThrow();
    }

    private long rowCount(String table, long organizationId) {
        return countWhere(table + " WHERE organization_id = ?", organizationId);
    }

    private Map<String, Object> onlyRow(String table, long organizationId) {
        assertThat(rowCount(table, organizationId))
                .as("%s must hold exactly one row for cooperative %s", table, organizationId)
                .isEqualTo(1);
        return jdbcTemplate.queryForMap(
                "SELECT * FROM " + table + " WHERE organization_id = ?", organizationId);
    }

    private long idOf(String table, long organizationId) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM " + table + " WHERE organization_id = ?", Long.class,
                organizationId);
        assertThat(id).as("%s must hold a row for cooperative %s", table, organizationId)
                .isNotNull();
        return id;
    }

    private JsonNode read(ResultActions performed) throws Exception {
        return objectMapper.readTree(performed.andReturn().getResponse().getContentAsString());
    }

    private static BigDecimal amount(Map<String, Object> row, String column) {
        return (BigDecimal) row.get(column);
    }

    /** {@code integer} and {@code smallint} come back as different Java types; compare as ints. */
    private static Integer number(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? null : ((Number) value).intValue();
    }
}
