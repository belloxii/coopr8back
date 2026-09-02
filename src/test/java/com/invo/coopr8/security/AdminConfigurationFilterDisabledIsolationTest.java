package com.invo.coopr8.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.invo.coopr8.configuration.ConfigProvisioner;
import com.invo.coopr8.support.AbstractTwoTenantTest;
import com.invo.coopr8.tenant.ActiveTenant;
import com.invo.coopr8.tenant.TenantContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The configuration tenant boundary, with the Hibernate tenant filter switched off.
 *
 * <p>{@code TenantFilterDisabledIsolationTest} does this for the business surface -- loans, savings,
 * shares, notifications, members. This class does it for the configuration surface, which that class
 * does not touch, and which is filter-sensitive in a way the business surface is not.
 *
 * <h2>Why configuration is the worse case</h2>
 * Most business lookups are {@code findByIdAndOrganizationId}: a missing tenant predicate returns
 * somebody else's row and a caller sees data that is not theirs. Bad, and that is what the sibling
 * class proves closed. But the four singleton configuration tables are read by
 * {@code findByOrganizationId} with <em>no id at all</em> -- there is one loan configuration per
 * cooperative and the cooperative is the only key there is. Drop that predicate with the filter off
 * and the query does not return "the wrong row for a known id"; it returns <em>whichever row the
 * database hands back first</em>, or throws on finding several. So the first cooperative to open its
 * loan settings could be shown another cooperative's interest rate as its own, and
 * {@link ConfigProvisioner} -- which decides whether to insert by asking exactly that question --
 * could conclude a cooperative is already configured because a different one is.
 * {@link #provisioningStillGivesEachCooperativeItsOwnRowWithTheFilterOff()} is the test for that, and
 * it is the reason this class exists rather than being folded into its sibling.
 *
 * <h2>It cannot pass vacuously</h2>
 * {@link #theFilterReallyIsOffInThisContext()} runs a JPQL count over a configuration entity that the
 * filter would have restricted and observes that it was not restricted. Without that guard a typo in
 * the property name would leave the filter on and every assertion below would be proving the filter
 * works rather than that the repositories do.
 *
 * <p>{@code @TestPropertySource} gives this class its own application context, so the property cannot
 * leak into the suites that expect the filter on.
 */
@TestPropertySource(properties = "coopr8.tenant.hibernate-filter.enabled=false")
class AdminConfigurationFilterDisabledIsolationTest extends AbstractTwoTenantTest {

    private static final String BASE = "/api/admin/config";

    private static final String BETA_RATE = "13.500";
    private static final String BETA_SHARE_PRICE = "777.00";
    private static final String BETA_PRODUCT = "Beta Special Loan";
    private static final String BETA_SECOND_PRODUCT = "Beta Housing Loan";
    private static final String BETA_PLAN = "Beta Monthly Plan";

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // ============================================================================ vacuity guard

    @Test
    @DisplayName("The Hibernate tenant filter really is off in this context")
    void theFilterReallyIsOffInThisContext() throws Exception {
        createProduct(alphaAdminToken, "Alpha Loan");
        createProduct(betaAdminToken, BETA_PRODUCT);

        // OrganizationLoanType carries @Filter, and a tenant is bound, so with the filter on this
        // counts one cooperative's products (1) and with it off it counts the whole table (2).
        Long visible;
        TenantContext.bind(new ActiveTenant(alpha.organizationId(), ALPHA_SLUG));
        try {
            visible = new TransactionTemplate(transactionManager).execute(transaction ->
                    entityManager.createQuery(
                            "SELECT count(t) FROM OrganizationLoanType t", Long.class)
                            .getSingleResult());
        } finally {
            // Binding by hand is something only this test does. Leaving it bound would make the next
            // request's bind() refuse to rebind and fail for an unrelated reason.
            TenantContext.clear();
        }

        assertThat(visible)
                .as("with the filter disabled an unscoped JPQL query over a configuration entity "
                        + "sees every cooperative's rows -- which is exactly the exposure the tests "
                        + "below prove the repository predicates close on their own")
                .isEqualTo(2L);
        assertThat(countWhere("organization_loan_type")).isEqualTo(2);
    }

    // ==================================================== the singleton configuration tables

    @Test
    @DisplayName("Provisioning still gives each cooperative its own row with the filter off")
    void provisioningStillGivesEachCooperativeItsOwnRowWithTheFilterOff() throws Exception {
        // Beta goes first and sets values nothing else would produce.
        seedBetaConfiguration();
        assertThat(countWhere("organization_loan_config")).isEqualTo(1);

        // Now Alpha opens its own settings screen for the first time. The provisioner asks
        // "is there a loan configuration for this cooperative?" using findByOrganizationId. With the
        // filter off, nothing but that method's own predicate stops the question being answered with
        // Beta's row -- in which case Alpha is handed Beta's interest rate as its own and no row is
        // ever inserted for Alpha at all.
        JsonNode loan = read(as(alphaAdminToken, get(BASE + "/loan")).andExpect(status().isOk()));

        assertThat(loan.path("interestRate").decimalValue())
                .as("Alpha must be shown its own defaults, not Beta's administered rate")
                .isEqualByComparingTo(ConfigProvisioner.DEFAULT_INTEREST_RATE);
        assertThat(countWhere("organization_loan_config")).isEqualTo(2);
        assertThat(countWhere("organization_loan_config WHERE organization_id = ?",
                alpha.organizationId())).isEqualTo(1);
        assertThat(storedRate(alpha.organizationId()))
                .isEqualByComparingTo(ConfigProvisioner.DEFAULT_INTEREST_RATE);
        assertThat(storedRate(beta.organizationId()))
                .isEqualByComparingTo(new BigDecimal(BETA_RATE));

        // The same question for the other three singleton tables.
        JsonNode shares = read(as(alphaAdminToken, get(BASE + "/shares")).andExpect(status().isOk()));
        assertThat(shares.path("sharePrice").decimalValue())
                .isEqualByComparingTo(ConfigProvisioner.DEFAULT_SHARE_PRICE);
        as(alphaAdminToken, get(BASE + "/repayment")).andExpect(status().isOk());
        JsonNode membership = read(as(alphaAdminToken, get(BASE + "/membership"))
                .andExpect(status().isOk()));
        assertThat(membership.path("requirePsn").asBoolean())
                .as("Beta requires a PSN; Alpha never asked to")
                .isEqualTo(ConfigProvisioner.DEFAULT_REQUIRE_PSN);

        for (String table : List.of("organization_shares_config", "organization_repayment_config",
                "organization_membership_config")) {
            assertThat(countWhere(table)).as("%s must hold one row per cooperative", table)
                    .isEqualTo(2);
        }
    }

    @Test
    @DisplayName("A write to a singleton configuration table still lands only on the caller's own")
    void aSingletonWriteStillLandsOnlyOnTheCallersOwnWithTheFilterOff() throws Exception {
        seedBetaConfiguration();

        as(alphaAdminToken, get(BASE + "/loan")).andExpect(status().isOk());
        as(alphaAdminToken, put(BASE + "/loan"), Map.of(
                "interestMethod", "FLAT",
                "interestRate", "4.000",
                "requiredGuarantors", 2,
                "reason", "Alpha board resolution"))
                .andExpect(status().isOk());

        as(alphaAdminToken, put(BASE + "/shares"), Map.of(
                "sharePrice", "300.00",
                "approvalRequired", true,
                "withdrawalAllowed", true,
                "reason", "Alpha share price review"))
                .andExpect(status().isOk());

        assertThat(storedRate(alpha.organizationId())).isEqualByComparingTo(new BigDecimal("4.000"));
        assertThat(storedRate(beta.organizationId()))
                .as("Beta's rate must survive Alpha's write with the filter off")
                .isEqualByComparingTo(new BigDecimal(BETA_RATE));
        assertThat(storedSharePrice(alpha.organizationId()))
                .isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(storedSharePrice(beta.organizationId()))
                .isEqualByComparingTo(new BigDecimal(BETA_SHARE_PRICE));

        // Still one row each: an UPDATE that matched both would have left two rows agreeing.
        assertThat(countWhere("organization_loan_config")).isEqualTo(2);
        assertThat(countWhere("organization_shares_config")).isEqualTo(2);
    }

    // =========================================================== another cooperative's rows

    @Test
    @DisplayName("Another cooperative's configuration rows are still 404 with the filter off")
    void anotherCooperativesRowsAreStill404WithTheFilterOff() throws Exception {
        Beta beta = seedBetaConfiguration();
        long betaAuditRows = countWhere("organization_config_audit WHERE organization_id = ?",
                this.beta.organizationId());

        Map<String, Integer> notFourOhFour = new LinkedHashMap<>();
        probe(notFourOhFour, "GET /loan-types/{beta}",
                get(BASE + "/loan-types/{id}", beta.productId()), null);
        probe(notFourOhFour, "PUT /loan-types/{beta}",
                put(BASE + "/loan-types/{id}", beta.productId()), productBody("Stolen Product"));
        probe(notFourOhFour, "DELETE /loan-types/{beta}",
                delete(BASE + "/loan-types/{id}", beta.productId()), null);
        probe(notFourOhFour, "GET /savings-plans/{beta}",
                get(BASE + "/savings-plans/{id}", beta.planId()), null);
        probe(notFourOhFour, "PUT /savings-plans/{beta}",
                put(BASE + "/savings-plans/{id}", beta.planId()), planBody("Stolen Plan"));
        probe(notFourOhFour, "DELETE /savings-plans/{beta}",
                delete(BASE + "/savings-plans/{id}", beta.planId()), null);
        probe(notFourOhFour, "DELETE /loan-type-exclusions/{beta}",
                delete(BASE + "/loan-type-exclusions/{id}", beta.exclusionId()), null);

        assertThat(notFourOhFour)
                .as("with the filter off, findByIdAndOrganizationId is the only thing between "
                        + "these requests and Beta's configuration")
                .isEmpty();

        // Nothing was written on the way to being refused.
        assertThat(nameOf("organization_loan_type", beta.productId())).isEqualTo(BETA_PRODUCT);
        assertThat(activeOf("organization_loan_type", beta.productId())).isTrue();
        assertThat(nameOf("organization_savings_plan", beta.planId())).isEqualTo(BETA_PLAN);
        assertThat(activeOf("organization_savings_plan", beta.planId())).isTrue();
        assertThat(countWhere("organization_loan_type_exclusion WHERE id = ?", beta.exclusionId()))
                .isEqualTo(1);
        assertThat(countWhere("organization_config_audit WHERE organization_id = ?",
                this.beta.organizationId())).isEqualTo(betaAuditRows);

        // Positive control: the same endpoints work inside the caller's own cooperative, so the 404s
        // above are about the tenant and not about a context that failed to start.
        long ownProduct = createProduct(alphaAdminToken, "Alpha Loan");
        as(alphaAdminToken, get(BASE + "/loan-types/{id}", ownProduct))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ownProduct));
        as(alphaAdminToken, delete(BASE + "/loan-types/{id}", ownProduct)
                .param("reason", "Withdrawn in test"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("An exclusion still cannot span the tenant boundary with the filter off")
    void anExclusionStillCannotSpanTheTenantBoundaryWithTheFilterOff() throws Exception {
        Beta beta = seedBetaConfiguration();
        long alphaProduct = createProduct(alphaAdminToken, "Alpha Loan");

        as(alphaAdminToken, post(BASE + "/loan-type-exclusions"), Map.of(
                "loanTypeId", alphaProduct, "excludedLoanTypeId", beta.productId()))
                .andExpect(status().isNotFound());
        as(alphaAdminToken, post(BASE + "/loan-type-exclusions"), Map.of(
                "loanTypeId", beta.productId(), "excludedLoanTypeId", alphaProduct))
                .andExpect(status().isNotFound());
        as(alphaAdminToken, post(BASE + "/loan-type-exclusions"), Map.of(
                "loanTypeId", beta.productId(), "excludedLoanTypeId", beta.secondProductId()))
                .andExpect(status().isNotFound());

        assertThat(countWhere("organization_loan_type_exclusion WHERE organization_id = ?",
                alpha.organizationId())).isZero();
        assertThat(countWhere("organization_loan_type_exclusion WHERE organization_id = ?",
                this.beta.organizationId())).isEqualTo(1);
    }

    // ======================================================================= the collections

    @Test
    @DisplayName("Configuration collections are still tenant-scoped with the filter off")
    void configurationCollectionsAreStillTenantScopedWithTheFilterOff() throws Exception {
        seedBetaConfiguration();

        // findAllByOrganizationId... with nothing behind it. A list endpoint that had quietly become
        // findAll() would look correct in every other suite, because the filter would hide it.
        for (String path : List.of("/loan-types", "/savings-plans", "/loan-type-exclusions",
                "/audit")) {
            as(alphaAdminToken, get(BASE + path))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        String everything = as(alphaAdminToken, get(BASE + "/loan-types"))
                .andReturn().getResponse().getContentAsString()
                + as(alphaAdminToken, get(BASE + "/savings-plans"))
                        .andReturn().getResponse().getContentAsString()
                + as(alphaAdminToken, get(BASE + "/audit"))
                        .andReturn().getResponse().getContentAsString();
        assertThat(everything).doesNotContain(BETA_PRODUCT, BETA_SECOND_PRODUCT, BETA_PLAN,
                beta.admin().ledgerID());

        // Beta still sees its own, so the empty lists are not an application that stopped working.
        as(betaAdminToken, get(BASE + "/loan-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        as(betaAdminToken, get(BASE + "/savings-plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        as(betaAdminToken, get(BASE + "/loan-type-exclusions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("The audit trail is still tenant-scoped with the filter off")
    void theAuditTrailIsStillTenantScopedWithTheFilterOff() throws Exception {
        seedBetaConfiguration();

        as(alphaAdminToken, get(BASE + "/loan")).andExpect(status().isOk());
        as(alphaAdminToken, put(BASE + "/loan"), Map.of(
                "interestMethod", "FLAT",
                "interestRate", "4.000",
                "requiredGuarantors", 2,
                "reason", "Alpha board resolution"))
                .andExpect(status().isOk());

        JsonNode alphaTrail = read(as(alphaAdminToken, get(BASE + "/audit"))
                .andExpect(status().isOk()));
        assertThat(alphaTrail).isNotEmpty();
        alphaTrail.forEach(entry ->
                assertThat(entry.path("actorLedgerId").asText())
                        .isEqualTo(alpha.admin().ledgerID()));

        JsonNode betaTrail = read(as(betaAdminToken, get(BASE + "/audit"))
                .andExpect(status().isOk()));
        assertThat(betaTrail).isNotEmpty();
        betaTrail.forEach(entry ->
                assertThat(entry.path("actorLedgerId").asText()).isEqualTo(beta.admin().ledgerID()));

        // The trail is append-only history: an unscoped read would show one cooperative every
        // configuration decision the other has ever made, with the name of the person who made it.
        assertThat(alphaTrail.size() + betaTrail.size())
                .isEqualTo((int) countWhere("organization_config_audit"));
    }

    // ============================================================================= Beta's setup

    private Beta seedBetaConfiguration() throws Exception {
        as(betaAdminToken, get(BASE + "/loan")).andExpect(status().isOk());
        as(betaAdminToken, put(BASE + "/loan"), Map.of(
                "interestMethod", "FLAT",
                "interestRate", BETA_RATE,
                "requiredGuarantors", 0,
                "reason", "Beta board resolution"))
                .andExpect(status().isOk());

        as(betaAdminToken, get(BASE + "/shares")).andExpect(status().isOk());
        as(betaAdminToken, put(BASE + "/shares"), Map.of(
                "sharePrice", BETA_SHARE_PRICE,
                "approvalRequired", false,
                "withdrawalAllowed", false,
                "reason", "Beta share price review"))
                .andExpect(status().isOk());

        as(betaAdminToken, get(BASE + "/membership")).andExpect(status().isOk());
        Map<String, Object> membership = new LinkedHashMap<>();
        membership.put("requireEmail", true);
        membership.put("requirePhone", true);
        membership.put("requirePsn", true);
        membership.put("requirePassport", true);
        membership.put("requireNextOfKin", false);
        membership.put("autoActivateMembers", false);
        membership.put("defaultMemberStatus", "PENDING");
        as(betaAdminToken, put(BASE + "/membership"), membership).andExpect(status().isOk());

        long productId = createProduct(betaAdminToken, BETA_PRODUCT);
        long secondProductId = createProduct(betaAdminToken, BETA_SECOND_PRODUCT);
        long planId = createPlan(betaAdminToken, BETA_PLAN);
        long exclusionId = read(as(betaAdminToken, post(BASE + "/loan-type-exclusions"), Map.of(
                "loanTypeId", productId, "excludedLoanTypeId", secondProductId))
                .andExpect(status().isOk())).path("id").asLong();

        return new Beta(productId, secondProductId, planId, exclusionId);
    }

    private record Beta(long productId, long secondProductId, long planId, long exclusionId) {
    }

    // ================================================================================= helpers

    private Map<String, Object> productBody(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("description", "A loan product.");
        body.put("interestMethod", "FLAT");
        body.put("interestRate", "9.000");
        body.put("minLoanAmount", "5000.00");
        body.put("maxLoanAmount", "500000.00");
        body.put("minTenureMonths", 3);
        body.put("maxTenureMonths", 12);
        body.put("maxActiveLoans", 1);
        return body;
    }

    private Map<String, Object> planBody(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("description", "A contribution plan.");
        body.put("planAmount", "5000.00");
        body.put("frequency", "MONTHLY");
        return body;
    }

    private long createProduct(String bearerToken, String name) throws Exception {
        return read(as(bearerToken, post(BASE + "/loan-types"), productBody(name))
                .andExpect(status().isOk())).path("id").asLong();
    }

    private long createPlan(String bearerToken, String name) throws Exception {
        return read(as(bearerToken, post(BASE + "/savings-plans"), planBody(name))
                .andExpect(status().isOk())).path("id").asLong();
    }

    /** Alpha's administrator against the given request; records anything that is not a 404. */
    private void probe(Map<String, Integer> failures, String label,
            MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json(body));
        }
        int status = mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, alphaAdminToken))
                .andReturn().getResponse().getStatus();
        if (status != 404) {
            failures.put(label, status);
        }
    }

    private BigDecimal storedRate(long organizationId) {
        return jdbcTemplate.queryForObject(
                "SELECT interest_rate FROM organization_loan_config WHERE organization_id = ?",
                BigDecimal.class, organizationId);
    }

    private BigDecimal storedSharePrice(long organizationId) {
        return jdbcTemplate.queryForObject(
                "SELECT share_price FROM organization_shares_config WHERE organization_id = ?",
                BigDecimal.class, organizationId);
    }

    private String nameOf(String table, long id) {
        return jdbcTemplate.queryForObject("SELECT name FROM " + table + " WHERE id = ?",
                String.class, id);
    }

    private Boolean activeOf(String table, long id) {
        return jdbcTemplate.queryForObject("SELECT active FROM " + table + " WHERE id = ?",
                Boolean.class, id);
    }

    private JsonNode read(ResultActions performed) throws Exception {
        return objectMapper.readTree(performed.andReturn().getResponse().getContentAsString());
    }
}
