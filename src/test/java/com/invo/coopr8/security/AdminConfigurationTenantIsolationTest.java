package com.invo.coopr8.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.invo.coopr8.configuration.ConfigProvisioner;
import com.invo.coopr8.controller.AdminConfigController;
import com.invo.coopr8.support.AbstractTwoTenantTest;

/**
 * The tenant boundary around the administrator configuration surface.
 *
 * <p>Configuration is the highest-value target in a multi-tenant cooperative platform. It is not a
 * list of preferences: it holds the interest rate members are charged, the price of a share, how many
 * guarantors a loan needs and whether a new member is active on arrival. An administrator who could
 * read another cooperative's configuration would know its commercial terms; one who could write it
 * could reprice its loans. So every endpoint under {@code /api/admin/config} has to answer two
 * questions the same way every time: <em>which cooperative is this</em> (the token's, never the
 * request's) and <em>what happens when the row belongs to somebody else</em> (404, never 403).
 *
 * <h2>Why 404 and not 403</h2>
 * A 403 on another cooperative's loan-product id confirms the id exists. Iterating ids would then map
 * out a competitor's product catalogue without ever reading a single one. Absent and not-yours are
 * deliberately the same answer, and {@link #anotherCooperativesRowIsAlways404NeverA403()} asserts
 * that specifically rather than accepting any 4xx.
 *
 * <h2>What this class does not repeat</h2>
 * Tenant <em>authentication</em> isolation -- a membership number valid only inside its own
 * cooperative, the token's cooperative winning over anything the caller supplies, a request naming no
 * cooperative failing closed -- is {@code TenantAwareAuthenticationTest}'s subject and is already
 * proven there. The business surface's cross-tenant behaviour is {@code CrossTenantIsolationTest}'s
 * and {@code AuthorizationBoundaryTest}'s. This class covers the configuration surface, which none of
 * them touches.
 */
class AdminConfigurationTenantIsolationTest extends AbstractTwoTenantTest {

    private static final String BASE = "/api/admin/config";

    /** Beta's own settings, chosen so any leak into Alpha's answers is unmistakable. */
    private static final String BETA_RATE = "13.500";
    private static final String BETA_SHARE_PRICE = "777.00";
    private static final int BETA_GUARANTORS = 0;
    private static final String BETA_PRODUCT = "Beta Special Loan";
    private static final String BETA_SECOND_PRODUCT = "Beta Housing Loan";
    private static final String BETA_PLAN = "Beta Monthly Plan";

    // ------------------------------------------------------------------ the sweep is complete

    @Test
    @DisplayName("The sweep below covers every endpoint the configuration controller declares")
    void theSweepCoversEveryConfigurationEndpoint() {
        // A new configuration endpoint added without a line in `everyEndpoint` would otherwise be
        // reachable by a member, or across the tenant boundary, with nothing failing to say so.
        TreeSet<String> declared = new TreeSet<>();
        for (Method method : AdminConfigController.class.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            PostMapping post = method.getAnnotation(PostMapping.class);
            PutMapping put = method.getAnnotation(PutMapping.class);
            DeleteMapping remove = method.getAnnotation(DeleteMapping.class);
            if (get != null) {
                declared.add("GET " + first(get.value()));
            }
            if (post != null) {
                declared.add("POST " + first(post.value()));
            }
            if (put != null) {
                declared.add("PUT " + first(put.value()));
            }
            if (remove != null) {
                declared.add("DELETE " + first(remove.value()));
            }
        }

        TreeSet<String> swept = new TreeSet<>();
        everyEndpoint(1L, 2L, 3L, 4L).forEach(probe -> swept.add(probe.key()));

        assertThat(swept)
                .as("every declared configuration endpoint must be swept by this class")
                .isEqualTo(declared);
        assertThat(declared).hasSize(22);
    }

    // ----------------------------------------------------------------- authorization boundary

    @Test
    @DisplayName("A member reaches no configuration endpoint at all")
    void aMemberReachesNoConfigurationEndpoint() throws Exception {
        Beta beta = seedBetaConfiguration();
        long alphaProduct = createProduct(alphaAdminToken, "Alpha Loan");
        long alphaPlan = createPlan(alphaAdminToken, "Alpha Plan");
        long auditRowsBefore = countWhere("organization_config_audit");

        Map<String, Integer> wrong = new LinkedHashMap<>();
        for (Probe probe : everyEndpoint(alphaProduct, beta.productId(), 1L, alphaPlan)) {
            int status = perform(alphaMemberToken, probe).getResponse().getStatus();
            if (status != 403) {
                wrong.put(probe.key(), status);
            }
        }

        // 403 from the filter chain, before the controller is entered: /api/admin/** is
        // hasRole('ADMIN'), so a member's token never reaches the configuration services at all.
        assertThat(wrong)
                .as("a member must be refused 403 on every configuration endpoint")
                .isEmpty();

        // And the refusals wrote nothing on their way out.
        assertThat(countWhere("organization_config_audit")).isEqualTo(auditRowsBefore);
        assertThat(storedRate(beta.organizationId())).isEqualByComparingTo(new BigDecimal(BETA_RATE));
    }

    @Test
    @DisplayName("An unauthenticated caller reaches no configuration endpoint at all")
    void anUnauthenticatedCallerReachesNoConfigurationEndpoint() throws Exception {
        Map<String, Integer> wrong = new LinkedHashMap<>();
        for (Probe probe : everyEndpoint(1L, 2L, 3L, 4L)) {
            MockHttpServletRequestBuilder request = probe.request();
            if (probe.body() != null) {
                request = request.contentType(MediaType.APPLICATION_JSON).content(json(probe.body()));
            }
            int status = mockMvc.perform(request).andReturn().getResponse().getStatus();
            if (status != 401 && status != 403) {
                wrong.put(probe.key(), status);
            }
        }

        assertThat(wrong)
                .as("an anonymous caller must be refused 401 or 403 on every endpoint")
                .isEmpty();
        assertThat(countWhere("organization_loan_config")).isZero();
        assertThat(countWhere("organization_config_audit")).isZero();
    }

    // ------------------------------------------------------------------------- reads

    @Test
    @DisplayName("An administrator cannot read another cooperative's configuration")
    void anAdministratorCannotReadAnotherCooperativesConfiguration() throws Exception {
        Beta beta = seedBetaConfiguration();

        // Alpha's own settings screens show Alpha's defaults -- never Beta's numbers.
        JsonNode loan = read(as(alphaAdminToken, get(BASE + "/loan")).andExpect(status().isOk()));
        assertThat(loan.path("interestRate").decimalValue())
                .isEqualByComparingTo(ConfigProvisioner.DEFAULT_INTEREST_RATE);
        assertThat(loan.path("interestMethod").asText())
                .isEqualTo(ConfigProvisioner.DEFAULT_INTEREST_METHOD.name());
        assertThat(loan.path("requiredGuarantors").asInt())
                .isEqualTo(ConfigProvisioner.DEFAULT_REQUIRED_GUARANTORS);

        JsonNode shares = read(as(alphaAdminToken, get(BASE + "/shares")).andExpect(status().isOk()));
        assertThat(shares.path("sharePrice").decimalValue())
                .isEqualByComparingTo(ConfigProvisioner.DEFAULT_SHARE_PRICE);

        JsonNode membership = read(as(alphaAdminToken, get(BASE + "/membership"))
                .andExpect(status().isOk()));
        assertThat(membership.path("requirePsn").asBoolean())
                .isEqualTo(ConfigProvisioner.DEFAULT_REQUIRE_PSN);

        // And Alpha's collections are empty, rather than showing Beta's catalogue.
        as(alphaAdminToken, get(BASE + "/loan-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        as(alphaAdminToken, get(BASE + "/savings-plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        as(alphaAdminToken, get(BASE + "/loan-type-exclusions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        as(alphaAdminToken, get(BASE + "/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // Not even the names leak. A product name is commercial information.
        String everything = as(alphaAdminToken, get(BASE + "/loan-types"))
                .andReturn().getResponse().getContentAsString()
                + as(alphaAdminToken, get(BASE + "/savings-plans"))
                        .andReturn().getResponse().getContentAsString()
                + as(alphaAdminToken, get(BASE + "/audit"))
                        .andReturn().getResponse().getContentAsString();
        assertThat(everything)
                .doesNotContain(BETA_PRODUCT, BETA_SECOND_PRODUCT, BETA_PLAN, BETA_RATE,
                        BETA_SHARE_PRICE, beta.adminLedgerId());
    }

    @Test
    @DisplayName("Another cooperative's row is always 404, never a 403 that would confirm it exists")
    void anotherCooperativesRowIsAlways404NeverA403() throws Exception {
        Beta beta = seedBetaConfiguration();

        List<Probe> byId = List.of(
                new Probe("GET /loan-types/{id}",
                        get(BASE + "/loan-types/{id}", beta.productId()), null),
                new Probe("PUT /loan-types/{id}",
                        put(BASE + "/loan-types/{id}", beta.productId()), productBody("Renamed")),
                new Probe("DELETE /loan-types/{id}",
                        delete(BASE + "/loan-types/{id}", beta.productId()), null),
                new Probe("GET /savings-plans/{id}",
                        get(BASE + "/savings-plans/{id}", beta.planId()), null),
                new Probe("PUT /savings-plans/{id}",
                        put(BASE + "/savings-plans/{id}", beta.planId()), planBody("Renamed")),
                new Probe("DELETE /savings-plans/{id}",
                        delete(BASE + "/savings-plans/{id}", beta.planId()), null),
                new Probe("DELETE /loan-type-exclusions/{id}",
                        delete(BASE + "/loan-type-exclusions/{id}", beta.exclusionId()), null));

        Map<String, Integer> wrong = new LinkedHashMap<>();
        for (Probe probe : byId) {
            int status = perform(alphaAdminToken, probe).getResponse().getStatus();
            if (status != 404) {
                wrong.put(probe.key(), status);
            }
        }

        assertThat(wrong)
                .as("another cooperative's id must be indistinguishable from one that never existed")
                .isEmpty();

        // The same answer a nonexistent id gives, word for word.
        as(alphaAdminToken, get(BASE + "/loan-types/{id}", 9_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.responseMessage").value("Not found."));
        as(alphaAdminToken, get(BASE + "/loan-types/{id}", beta.productId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.responseMessage").value("Not found."));
    }

    // ------------------------------------------------------------------------- writes

    @Test
    @DisplayName("An administrator cannot modify another cooperative's configuration")
    void anAdministratorCannotModifyAnotherCooperativesConfiguration() throws Exception {
        Beta beta = seedBetaConfiguration();
        long betaAuditRows = countWhere("organization_config_audit WHERE organization_id = ?",
                beta.organizationId());

        Map<String, Object> rename = productBody("Stolen Product");
        rename.put("interestRate", "1.000");
        rename.put("reason", "Not mine to change");
        as(alphaAdminToken, put(BASE + "/loan-types/{id}", beta.productId()), rename)
                .andExpect(status().isNotFound());

        as(alphaAdminToken, delete(BASE + "/loan-types/{id}", beta.productId())
                .param("reason", "Not mine to withdraw"))
                .andExpect(status().isNotFound());

        Map<String, Object> replan = planBody("Stolen Plan");
        replan.put("planAmount", "1.00");
        replan.put("reason", "Not mine to change");
        as(alphaAdminToken, put(BASE + "/savings-plans/{id}", beta.planId()), replan)
                .andExpect(status().isNotFound());

        as(alphaAdminToken, delete(BASE + "/savings-plans/{id}", beta.planId()))
                .andExpect(status().isNotFound());

        as(alphaAdminToken, delete(BASE + "/loan-type-exclusions/{id}", beta.exclusionId()))
                .andExpect(status().isNotFound());

        // Beta's rows are exactly as Beta left them.
        assertThat(nameOf("organization_loan_type", beta.productId())).isEqualTo(BETA_PRODUCT);
        assertThat(activeOf("organization_loan_type", beta.productId())).isTrue();
        assertThat(nameOf("organization_savings_plan", beta.planId())).isEqualTo(BETA_PLAN);
        assertThat(activeOf("organization_savings_plan", beta.planId())).isTrue();
        assertThat(countWhere("organization_loan_type_exclusion WHERE id = ?", beta.exclusionId()))
                .isEqualTo(1);
        assertThat(storedRate(beta.organizationId())).isEqualByComparingTo(new BigDecimal(BETA_RATE));

        // Nothing was appended to Beta's trail either. A refused attempt is not Beta's business, and
        // an audit row attributed to Alpha's administrator inside Beta would be a worse leak than the
        // change itself.
        assertThat(countWhere("organization_config_audit WHERE organization_id = ?",
                beta.organizationId())).isEqualTo(betaAuditRows);
        assertThat(countWhere("organization_config_audit WHERE actor_ledger_id = ?"
                + " AND organization_id = ?", alpha.admin().ledgerID(), beta.organizationId()))
                .isZero();
    }

    @Test
    @DisplayName("A cooperative's own settings are unaffected by another's, and writes stay home")
    void writesLandOnlyInTheCallersOwnCooperative() throws Exception {
        Beta beta = seedBetaConfiguration();

        as(alphaAdminToken, get(BASE + "/loan")).andExpect(status().isOk());
        as(alphaAdminToken, put(BASE + "/loan"), Map.of(
                "interestMethod", "FLAT",
                "interestRate", "4.000",
                "requiredGuarantors", 2,
                "reason", "Alpha board resolution"))
                .andExpect(status().isOk());

        assertThat(storedRate(alpha.organizationId())).isEqualByComparingTo(new BigDecimal("4.000"));
        assertThat(storedRate(beta.organizationId()))
                .as("Beta's rate must be untouched by Alpha's change")
                .isEqualByComparingTo(new BigDecimal(BETA_RATE));

        // Each cooperative has exactly one loan configuration row, and it is its own.
        assertThat(countWhere("organization_loan_config")).isEqualTo(2);
        assertThat(countWhere("organization_loan_config WHERE organization_id = ?"
                + " AND interest_rate = ?", alpha.organizationId(), new BigDecimal("4.000")))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("An exclusion cannot be created across the tenant boundary")
    void anExclusionCannotSpanTheTenantBoundary() throws Exception {
        Beta beta = seedBetaConfiguration();
        long alphaProduct = createProduct(alphaAdminToken, "Alpha Loan");

        // Both directions: the service must scope BOTH ids, not just the first one it looks up.
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
                beta.organizationId()))
                .as("Beta's own exclusion must be the only one, and unchanged")
                .isEqualTo(1);
    }

    // --------------------------------------------------- no post-authentication tenant selector

    @Test
    @DisplayName("The cooperative configured is the token's, whatever the request tries to name")
    void theCooperativeConfiguredIsTheTokens() throws Exception {
        Beta beta = seedBetaConfiguration();
        as(alphaAdminToken, get(BASE + "/loan")).andExpect(status().isOk());

        // Everything a caller might use to redirect the write: extra body fields under every name
        // the domain uses, and the same as query parameters. None of them is a parameter of any
        // configuration endpoint, so all of them must be inert.
        Map<String, Object> smuggled = new LinkedHashMap<>();
        smuggled.put("interestMethod", "FLAT");
        smuggled.put("interestRate", "6.000");
        smuggled.put("requiredGuarantors", 1);
        smuggled.put("reason", "Attempted redirection");
        smuggled.put("organizationId", beta.organizationId());
        smuggled.put("organization", BETA_SLUG);
        smuggled.put("organizationSlug", BETA_SLUG);
        smuggled.put("organization_id", beta.organizationId());
        smuggled.put("tenantId", beta.organizationId());
        smuggled.put("tenant", BETA_SLUG);
        smuggled.put("ledgerPrefix", BETA_PREFIX);
        smuggled.put("id", beta.loanConfigId());

        as(alphaAdminToken, put(BASE + "/loan")
                .param("organizationId", String.valueOf(beta.organizationId()))
                .param("organization", BETA_SLUG)
                .param("tenantId", String.valueOf(beta.organizationId())), smuggled)
                .andExpect(status().isOk());

        // The change landed on Alpha, and Beta is untouched -- including the row whose id the body
        // named directly.
        assertThat(storedRate(alpha.organizationId())).isEqualByComparingTo(new BigDecimal("6.000"));
        assertThat(storedRate(beta.organizationId()))
                .isEqualByComparingTo(new BigDecimal(BETA_RATE));
        assertThat(countWhere("organization_config_audit WHERE organization_id = ?",
                alpha.organizationId())).isPositive();
        assertThat(countWhere("organization_config_audit WHERE actor_ledger_id = ?"
                + " AND organization_id <> ?", alpha.admin().ledgerID(), alpha.organizationId()))
                .isZero();
    }

    @Test
    @DisplayName("No configuration response carries a cooperative field to echo back")
    void noConfigurationResponseNamesACooperative() throws Exception {
        seedBetaConfiguration();
        long product = createProduct(alphaAdminToken, "Alpha Loan");
        long plan = createPlan(alphaAdminToken, "Alpha Plan");

        // A response that named its cooperative would invite a client to send it back, and the next
        // person to add an endpoint would find a plausible-looking field waiting to be trusted.
        for (String path : new String[] { "/loan", "/shares", "/repayment", "/membership",
                "/loan-types", "/loan-types/" + product, "/savings-plans", "/savings-plans/" + plan,
                "/loan-type-exclusions", "/audit" }) {
            JsonNode body = read(as(alphaAdminToken, get(BASE + path)).andExpect(status().isOk()));
            Iterable<JsonNode> entries = body.isArray() ? body : List.of(body);
            for (JsonNode entry : entries) {
                entry.fieldNames().forEachRemaining(field -> assertThat(field.toLowerCase())
                        .as("%s must not expose %s", path, field)
                        .doesNotContain("organization")
                        .doesNotContain("tenant")
                        .doesNotContain("slug"));
            }
        }
    }

    // ------------------------------------------------------------------------ the audit trail

    @Test
    @DisplayName("Each administrator's trail contains only their own cooperative's changes")
    void eachTrailContainsOnlyItsOwnCooperativesChanges() throws Exception {
        Beta beta = seedBetaConfiguration();

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
        alphaTrail.forEach(entry -> {
            assertThat(entry.path("actorLedgerId").asText()).isEqualTo(alpha.admin().ledgerID());
            assertThat(entry.path("reason").asText("")).doesNotContain("Beta");
        });

        JsonNode betaTrail = read(as(betaAdminToken, get(BASE + "/audit"))
                .andExpect(status().isOk()));
        assertThat(betaTrail).isNotEmpty();
        betaTrail.forEach(entry ->
                assertThat(entry.path("actorLedgerId").asText()).isEqualTo(beta.adminLedgerId()));

        // The trails are disjoint, and every stored row names the cooperative it happened in.
        assertThat(countWhere("organization_config_audit WHERE actor_ledger_id LIKE ?"
                + " AND organization_id <> ?", ALPHA_PREFIX + "%", alpha.organizationId()))
                .isZero();
        assertThat(countWhere("organization_config_audit WHERE actor_ledger_id LIKE ?"
                + " AND organization_id <> ?", BETA_PREFIX + "%", beta.organizationId()))
                .isZero();
        assertThat(countWhere("organization_config_audit WHERE organization_id IS NULL")).isZero();
    }

    // -------------------------------------------------------------------- positive controls

    @Test
    @DisplayName("The same requests all succeed inside the caller's own cooperative")
    void theSameRequestsSucceedInsideTheCallersOwnCooperative() throws Exception {
        seedBetaConfiguration();

        // Without this, every 404 above could be an endpoint that is simply broken.
        long product = createProduct(alphaAdminToken, "Alpha Loan");
        long second = createProduct(alphaAdminToken, "Alpha Housing Loan");
        long plan = createPlan(alphaAdminToken, "Alpha Plan");
        long exclusion = read(as(alphaAdminToken, post(BASE + "/loan-type-exclusions"), Map.of(
                "loanTypeId", product, "excludedLoanTypeId", second))
                .andExpect(status().isOk())).path("id").asLong();

        Map<String, Integer> wrong = new LinkedHashMap<>();
        for (Probe probe : everyEndpoint(product, second, exclusion, plan)) {
            int status = perform(alphaAdminToken, probe).getResponse().getStatus();
            if (status != 200) {
                wrong.put(probe.key(), status);
            }
        }

        assertThat(wrong)
                .as("every configuration endpoint must work for its own cooperative's administrator")
                .isEmpty();
    }

    // ------------------------------------------------------------------------ the endpoint list

    /**
     * Every endpoint {@link AdminConfigController} declares, as a request that would succeed for the
     * cooperative owning the ids passed in.
     *
     * <p>{@code POST /loan-types} and {@code POST /savings-plans} use names nothing else creates, so
     * the positive-control sweep does not collide with its own fixtures. {@code POST
     * /loan-type-exclusions} pairs the two products, which is why it needs two of them.
     */
    private List<Probe> everyEndpoint(long productId, long secondProductId, long exclusionId,
            long planId) {
        List<Probe> probes = new ArrayList<>();

        probes.add(new Probe("GET /loan", get(BASE + "/loan"), null));
        probes.add(new Probe("PUT /loan", put(BASE + "/loan"), Map.of(
                "interestMethod", "FLAT", "interestRate", "3.000",
                "requiredGuarantors", 2, "reason", "Sweep")));

        probes.add(new Probe("GET /loan-types", get(BASE + "/loan-types"), null));
        probes.add(new Probe("GET /loan-types/{id}",
                get(BASE + "/loan-types/{id}", productId), null));
        probes.add(new Probe("POST /loan-types", post(BASE + "/loan-types"),
                productBody("Swept Product")));
        probes.add(new Probe("PUT /loan-types/{id}",
                put(BASE + "/loan-types/{id}", productId), sweptProductBody(productId)));
        probes.add(new Probe("DELETE /loan-types/{id}",
                delete(BASE + "/loan-types/{id}", productId).param("reason", "Sweep"), null));

        probes.add(new Probe("GET /loan-type-exclusions", get(BASE + "/loan-type-exclusions"),
                null));
        probes.add(new Probe("POST /loan-type-exclusions", post(BASE + "/loan-type-exclusions"),
                Map.of("loanTypeId", productId, "excludedLoanTypeId", secondProductId)));
        probes.add(new Probe("DELETE /loan-type-exclusions/{id}",
                delete(BASE + "/loan-type-exclusions/{id}", exclusionId), null));

        probes.add(new Probe("GET /savings-plans", get(BASE + "/savings-plans"), null));
        probes.add(new Probe("GET /savings-plans/{id}",
                get(BASE + "/savings-plans/{id}", planId), null));
        probes.add(new Probe("POST /savings-plans", post(BASE + "/savings-plans"),
                planBody("Swept Plan")));
        probes.add(new Probe("PUT /savings-plans/{id}",
                put(BASE + "/savings-plans/{id}", planId), sweptPlanBody(planId)));
        probes.add(new Probe("DELETE /savings-plans/{id}",
                delete(BASE + "/savings-plans/{id}", planId).param("reason", "Sweep"), null));

        probes.add(new Probe("GET /shares", get(BASE + "/shares"), null));
        probes.add(new Probe("PUT /shares", put(BASE + "/shares"), Map.of(
                "sharePrice", "300.00", "approvalRequired", true,
                "withdrawalAllowed", true, "reason", "Sweep")));

        probes.add(new Probe("GET /repayment", get(BASE + "/repayment"), null));
        probes.add(new Probe("PUT /repayment", put(BASE + "/repayment"), Map.of(
                "allowPartialRepayment", true, "allowOverpayment", true,
                "settlementTolerance", "0.50")));

        probes.add(new Probe("GET /membership", get(BASE + "/membership"), null));
        probes.add(new Probe("PUT /membership", put(BASE + "/membership"), membershipBody()));

        probes.add(new Probe("GET /audit", get(BASE + "/audit"), null));

        return probes;
    }

    // -------------------------------------------------------------------------- Beta's setup

    /** Beta's configuration, established through Beta's own administrator and Beta's own token. */
    private Beta seedBetaConfiguration() throws Exception {
        long loanConfigId = read(as(betaAdminToken, get(BASE + "/loan"))
                .andExpect(status().isOk())).path("id").asLong();

        as(betaAdminToken, put(BASE + "/loan"), Map.of(
                "interestMethod", "FLAT",
                "interestRate", BETA_RATE,
                "minLoanAmount", "20000.00",
                "maxLoanAmount", "3000000.00",
                "minTenureMonths", 6,
                "maxTenureMonths", 36,
                "requiredGuarantors", BETA_GUARANTORS,
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
        Map<String, Object> membership = membershipBody();
        membership.put("requirePsn", true);
        membership.put("requirePassport", true);
        as(betaAdminToken, put(BASE + "/membership"), membership).andExpect(status().isOk());

        long productId = createProduct(betaAdminToken, BETA_PRODUCT);
        long secondProductId = createProduct(betaAdminToken, BETA_SECOND_PRODUCT);
        long planId = createPlan(betaAdminToken, BETA_PLAN);
        long exclusionId = read(as(betaAdminToken, post(BASE + "/loan-type-exclusions"), Map.of(
                "loanTypeId", productId, "excludedLoanTypeId", secondProductId))
                .andExpect(status().isOk())).path("id").asLong();

        return new Beta(beta.organizationId(), beta.admin().ledgerID(), loanConfigId, productId,
                secondProductId, planId, exclusionId);
    }

    private record Beta(long organizationId, String adminLedgerId, long loanConfigId,
            long productId, long secondProductId, long planId, long exclusionId) {
    }

    private record Probe(String key, MockHttpServletRequestBuilder request, Object body) {
    }

    // ------------------------------------------------------------------------------ bodies

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

    /** A replacement that changes only the name, so it needs no reason and cannot collide. */
    private Map<String, Object> sweptProductBody(long productId) {
        Map<String, Object> body = productBody("Swept Rename " + productId);
        body.put("maxActiveLoans", 2);
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

    private Map<String, Object> sweptPlanBody(long planId) {
        Map<String, Object> body = planBody("Swept Rename " + planId);
        body.put("frequency", "WEEKLY");
        return body;
    }

    private Map<String, Object> membershipBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requireEmail", true);
        body.put("requirePhone", true);
        body.put("requirePsn", false);
        body.put("requirePassport", false);
        body.put("requireNextOfKin", false);
        body.put("autoActivateMembers", false);
        body.put("defaultMemberStatus", "PENDING");
        return body;
    }

    // ----------------------------------------------------------------------------- helpers

    private long createProduct(String bearerToken, String name) throws Exception {
        return read(as(bearerToken, post(BASE + "/loan-types"), productBody(name))
                .andExpect(status().isOk())).path("id").asLong();
    }

    private long createPlan(String bearerToken, String name) throws Exception {
        return read(as(bearerToken, post(BASE + "/savings-plans"), planBody(name))
                .andExpect(status().isOk())).path("id").asLong();
    }

    private MvcResult perform(String bearerToken, Probe probe) throws Exception {
        MockHttpServletRequestBuilder request = probe.request()
                .header(HttpHeaders.AUTHORIZATION, bearerToken);
        if (probe.body() != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json(probe.body()));
        }
        return mockMvc.perform(request).andReturn();
    }

    private BigDecimal storedRate(long organizationId) {
        return jdbcTemplate.queryForObject(
                "SELECT interest_rate FROM organization_loan_config WHERE organization_id = ?",
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

    private static String first(String[] paths) {
        return paths.length == 0 ? "" : paths[0];
    }
}
