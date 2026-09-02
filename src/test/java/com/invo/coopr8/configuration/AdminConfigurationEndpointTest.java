package com.invo.coopr8.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.invo.coopr8.support.AbstractTwoTenantTest;

/**
 * The administrator configuration surface, end to end, against a real PostgreSQL container.
 *
 * <p>{@link ConfigAuditWriter}'s own comment names this class for one assertion in particular:
 *
 * <blockquote>The reason check runs <em>before</em> any row is written, and throws. Since the caller
 * has already applied the change to a managed entity by then, the throw rolls the change back with
 * it. {@code AdminConfigurationEndpointTest} asserts exactly that: a rate change submitted without a
 * reason leaves the stored rate untouched.</blockquote>
 *
 * <p>That is {@link #aRateChangeWithNoReasonLeavesTheStoredRateUntouched()}, and it is why this class
 * has to hit a database rather than a mock. "The endpoint answered 400" proves nothing about whether
 * the rate was already saved by the time it did; only reading the column afterwards does.
 *
 * <p>Everything here runs as Alpha's administrator, in Alpha's own cooperative. The tenant boundary
 * is {@code AdminConfigurationTenantIsolationTest}'s subject; this class is about whether the surface
 * behaves correctly for the person entitled to use it. Where a test asserts a refusal, it also
 * asserts what the database holds afterwards -- a refusal that has already written is not a refusal.
 */
class AdminConfigurationEndpointTest extends AbstractTwoTenantTest {

    private static final String LOAN = "/api/admin/config/loan";
    private static final String LOAN_TYPES = "/api/admin/config/loan-types";
    private static final String EXCLUSIONS = "/api/admin/config/loan-type-exclusions";
    private static final String SAVINGS_PLANS = "/api/admin/config/savings-plans";
    private static final String SHARES = "/api/admin/config/shares";
    private static final String REPAYMENT = "/api/admin/config/repayment";
    private static final String MEMBERSHIP = "/api/admin/config/membership";
    private static final String AUDIT = "/api/admin/config/audit";

    // ------------------------------------------------------- the four singleton configurations

    @Test
    @DisplayName("The loan configuration round-trips a change")
    void theLoanConfigurationRoundTrips() throws Exception {
        as(alphaAdminToken, get(LOAN)).andExpect(status().isOk());

        Map<String, Object> body = loanBody();
        body.put("minLoanAmount", "10000.00");
        body.put("maxLoanAmount", "2000000.00");
        body.put("minTenureMonths", 3);
        body.put("maxTenureMonths", 24);

        JsonNode saved = read(as(alphaAdminToken, put(LOAN), body).andExpect(status().isOk()));
        assertThat(saved.path("interestMethod").asText()).isEqualTo("FLAT");
        assertThat(saved.path("interestRate").decimalValue())
                .isEqualByComparingTo(new BigDecimal("5.000"));
        assertThat(saved.path("requiredGuarantors").asInt()).isEqualTo(1);
        assertThat(saved.path("maxTenureMonths").asInt()).isEqualTo(24);

        // Read back through a second request, so the assertion is about what was stored rather than
        // about what the write path happened to return.
        JsonNode reread = read(as(alphaAdminToken, get(LOAN)).andExpect(status().isOk()));
        assertThat(reread.path("id").asLong()).isEqualTo(saved.path("id").asLong());
        assertThat(reread.path("interestMethod").asText()).isEqualTo("FLAT");
        assertThat(reread.path("interestRate").decimalValue())
                .isEqualByComparingTo(new BigDecimal("5.000"));
        assertThat(reread.path("minLoanAmount").decimalValue())
                .isEqualByComparingTo(new BigDecimal("10000.00"));
    }

    @Test
    @DisplayName("The shares configuration round-trips a change")
    void theSharesConfigurationRoundTrips() throws Exception {
        as(alphaAdminToken, get(SHARES)).andExpect(status().isOk());

        JsonNode saved = read(as(alphaAdminToken, put(SHARES), sharesBody())
                .andExpect(status().isOk()));
        assertThat(saved.path("sharePrice").decimalValue())
                .isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(saved.path("approvalRequired").asBoolean()).isFalse();
        assertThat(saved.path("withdrawalAllowed").asBoolean()).isFalse();

        assertThat(read(as(alphaAdminToken, get(SHARES))).path("sharePrice").decimalValue())
                .isEqualByComparingTo(new BigDecimal("250.00"));
    }

    @Test
    @DisplayName("The repayment configuration round-trips a change, and offers no penalty setting")
    void theRepaymentConfigurationRoundTrips() throws Exception {
        as(alphaAdminToken, get(REPAYMENT)).andExpect(status().isOk());

        JsonNode saved = read(as(alphaAdminToken, put(REPAYMENT), repaymentBody())
                .andExpect(status().isOk()));
        assertThat(saved.path("allowPartialRepayment").asBoolean()).isTrue();
        assertThat(saved.path("allowOverpayment").asBoolean()).isTrue();
        assertThat(saved.path("settlementTolerance").decimalValue())
                .isEqualByComparingTo(new BigDecimal("1.50"));

        // Decision 6: there is no penalty column, field or setting anywhere in the platform. A
        // response field named for one would mean somebody had added the column.
        saved.fieldNames().forEachRemaining(field ->
                assertThat(field.toLowerCase()).doesNotContain("penalt"));
    }

    @Test
    @DisplayName("The membership configuration round-trips a change")
    void theMembershipConfigurationRoundTrips() throws Exception {
        as(alphaAdminToken, get(MEMBERSHIP)).andExpect(status().isOk());

        Map<String, Object> body = membershipBody();
        body.put("requirePsn", true);
        body.put("requireNextOfKin", true);

        JsonNode saved = read(as(alphaAdminToken, put(MEMBERSHIP), body)
                .andExpect(status().isOk()));
        assertThat(saved.path("requirePsn").asBoolean()).isTrue();
        assertThat(saved.path("requireNextOfKin").asBoolean()).isTrue();
        assertThat(saved.path("defaultMemberStatus").asText()).isEqualTo("NEW");

        assertThat(read(as(alphaAdminToken, get(MEMBERSHIP))).path("requirePsn").asBoolean())
                .isTrue();
    }

    // ---------------------------------------------------------------------- loan products

    @Test
    @DisplayName("A loan product can be created, read, listed, replaced and withdrawn")
    void aLoanProductHasAFullLifecycle() throws Exception {
        JsonNode created = read(as(alphaAdminToken, post(LOAN_TYPES), productBody("Emergency Loan"))
                .andExpect(status().isOk()));
        long id = created.path("id").asLong();
        assertThat(created.path("name").asText()).isEqualTo("Emergency Loan");
        assertThat(created.path("active").asBoolean())
                .as("a product created without an explicit `active` is on offer")
                .isTrue();

        as(alphaAdminToken, get(LOAN_TYPES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id));

        as(alphaAdminToken, get(LOAN_TYPES + "/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Emergency Loan"));

        Map<String, Object> replacement = productBody("Emergency Loan");
        replacement.put("interestRate", "10.000");
        replacement.put("maxActiveLoans", 2);
        replacement.put("reason", "Rate review, board minute 12");
        as(alphaAdminToken, put(LOAN_TYPES + "/{id}", id), replacement)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxActiveLoans").value(2));

        // Withdrawal takes the product off offer; it does not delete it, because loans already
        // granted under it still point at it.
        as(alphaAdminToken, delete(LOAN_TYPES + "/{id}", id).param("reason", "No longer offered"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        as(alphaAdminToken, get(LOAN_TYPES + "/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        assertThat(countWhere("organization_loan_type WHERE organization_id = ?",
                alpha.organizationId())).isEqualTo(1);
    }

    @Test
    @DisplayName("Withdrawing an already withdrawn product records nothing further")
    void withdrawingTwiceIsIdempotent() throws Exception {
        long id = createProduct("Emergency Loan");

        as(alphaAdminToken, delete(LOAN_TYPES + "/{id}", id)).andExpect(status().isOk());
        as(alphaAdminToken, delete(LOAN_TYPES + "/{id}", id)).andExpect(status().isOk());

        assertThat(countWhere("organization_config_audit WHERE setting_key = ?", id + ".active"))
                .as("the second withdrawal changed nothing, so it must record nothing")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Two loan products cannot share a name, however it is spaced")
    void aDuplicateProductNameIsRefused() throws Exception {
        createProduct("Emergency Loan");

        // Whitespace is collapsed before the check, so "Emergency   Loan" is the same offer.
        as(alphaAdminToken, post(LOAN_TYPES), productBody("Emergency   Loan"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.responseCode").value("409"))
                .andExpect(jsonPath("$.responseMessage",
                        containsString("already offers a loan product called")));

        assertThat(countWhere("organization_loan_type WHERE organization_id = ?",
                alpha.organizationId())).isEqualTo(1);
    }

    @Test
    @DisplayName("An interest method the platform cannot calculate is not offered")
    void reducingBalanceIsNotSelectable() throws Exception {
        as(alphaAdminToken, get(LOAN)).andExpect(status().isOk());

        Map<String, Object> body = loanBody();
        body.put("interestMethod", "REDUCING_BALANCE");
        as(alphaAdminToken, put(LOAN), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseMessage",
                        containsString("REDUCING_BALANCE is not available yet")))
                .andExpect(jsonPath("$.responseMessage", containsString("NONE")))
                .andExpect(jsonPath("$.responseMessage", containsString("FLAT")));

        Map<String, Object> product = productBody("Reducing Balance Loan");
        product.put("interestMethod", "REDUCING_BALANCE");
        as(alphaAdminToken, post(LOAN_TYPES), product).andExpect(status().isBadRequest());

        // Nothing may reach the column: a stored REDUCING_BALANCE is a loan no engine can price.
        assertThat(countWhere("organization_loan_config WHERE interest_method = ?",
                "REDUCING_BALANCE")).isZero();
        assertThat(countWhere("organization_loan_type WHERE interest_method = ?",
                "REDUCING_BALANCE")).isZero();
    }

    @Test
    @DisplayName("A minimum above its maximum is refused")
    void invertedBoundsAreRefused() throws Exception {
        as(alphaAdminToken, get(LOAN)).andExpect(status().isOk());

        Map<String, Object> amounts = loanBody();
        amounts.put("minLoanAmount", "500000.00");
        amounts.put("maxLoanAmount", "1000.00");
        as(alphaAdminToken, put(LOAN), amounts)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseMessage",
                        containsString("cannot be more than the maximum")));

        Map<String, Object> tenures = loanBody();
        tenures.put("minTenureMonths", 24);
        tenures.put("maxTenureMonths", 6);
        as(alphaAdminToken, put(LOAN), tenures).andExpect(status().isBadRequest());

        assertThat(storedLoanRate()).isEqualByComparingTo(ConfigProvisioner.DEFAULT_INTEREST_RATE);
    }

    @Test
    @DisplayName("A guarantor requirement outside nought to two is refused")
    void theGuarantorCountIsBounded() throws Exception {
        for (int outOfRange : new int[] { -1, 3, 99 }) {
            Map<String, Object> body = loanBody();
            body.put("requiredGuarantors", outOfRange);
            as(alphaAdminToken, put(LOAN), body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.responseCode").value("419"));
        }
    }

    // ---------------------------------------------------------------------- savings plans

    @Test
    @DisplayName("A savings plan can be created, read, listed, replaced and withdrawn")
    void aSavingsPlanHasAFullLifecycle() throws Exception {
        JsonNode created = read(as(alphaAdminToken, post(SAVINGS_PLANS), planBody("Monthly Thrift"))
                .andExpect(status().isOk()));
        long id = created.path("id").asLong();
        assertThat(created.path("frequency").asText()).isEqualTo("MONTHLY");
        assertThat(created.path("active").asBoolean()).isTrue();

        as(alphaAdminToken, get(SAVINGS_PLANS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        as(alphaAdminToken, get(SAVINGS_PLANS + "/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Monthly Thrift"));

        Map<String, Object> replacement = planBody("Monthly Thrift");
        replacement.put("frequency", "WEEKLY");
        as(alphaAdminToken, put(SAVINGS_PLANS + "/{id}", id), replacement)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frequency").value("WEEKLY"));

        as(alphaAdminToken, delete(SAVINGS_PLANS + "/{id}", id).param("reason", "Withdrawn"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        assertThat(countWhere("organization_savings_plan WHERE organization_id = ?",
                alpha.organizationId())).isEqualTo(1);
    }

    @Test
    @DisplayName("An unrecognised contribution frequency is refused")
    void anUnknownFrequencyIsRefused() throws Exception {
        Map<String, Object> body = planBody("Fortnightly Thrift");
        body.put("frequency", "FORTNIGHTLY");

        as(alphaAdminToken, post(SAVINGS_PLANS), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseMessage",
                        containsString("is not a contribution frequency")));

        assertThat(countWhere("organization_savings_plan")).isZero();
    }

    @Test
    @DisplayName("Two savings plans cannot share a name")
    void aDuplicatePlanNameIsRefused() throws Exception {
        createPlan("Monthly Thrift");

        as(alphaAdminToken, post(SAVINGS_PLANS), planBody("Monthly Thrift"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.responseMessage",
                        containsString("already offers a savings plan called")));

        assertThat(countWhere("organization_savings_plan")).isEqualTo(1);
    }

    @Test
    @DisplayName("A savings plan must cost something")
    void aZeroPlanAmountIsRefused() throws Exception {
        Map<String, Object> body = planBody("Free Thrift");
        body.put("planAmount", "0.00");

        as(alphaAdminToken, post(SAVINGS_PLANS), body).andExpect(status().isBadRequest());
        assertThat(countWhere("organization_savings_plan")).isZero();
    }

    // ------------------------------------------------------------------------ exclusions

    @Test
    @DisplayName("A pair of mutually exclusive products can be created, listed and removed")
    void anExclusionHasAFullLifecycle() throws Exception {
        long first = createProduct("Emergency Loan");
        long second = createProduct("Housing Loan");

        JsonNode created = read(as(alphaAdminToken, post(EXCLUSIONS), Map.of(
                "loanTypeId", second, "excludedLoanTypeId", first))
                .andExpect(status().isOk()));

        // Stored lower-id-first so the same pair cannot be entered twice by swapping the order.
        assertThat(created.path("loanTypeId").asLong()).isEqualTo(Math.min(first, second));
        assertThat(created.path("excludedLoanTypeId").asLong()).isEqualTo(Math.max(first, second));
        assertThat(created.path("loanTypeName").asText()).isNotBlank();
        assertThat(created.path("excludedLoanTypeName").asText()).isNotBlank();

        as(alphaAdminToken, get(EXCLUSIONS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // Either order is the same rule.
        as(alphaAdminToken, post(EXCLUSIONS), Map.of(
                "loanTypeId", first, "excludedLoanTypeId", second))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.responseMessage",
                        containsString("are already mutually exclusive")));

        as(alphaAdminToken, delete(EXCLUSIONS + "/{id}", created.path("id").asLong()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCode").value("100"))
                .andExpect(jsonPath("$.responseMessage",
                        containsString("no longer mutually exclusive")));

        assertThat(countWhere("organization_loan_type_exclusion")).isZero();
    }

    @Test
    @DisplayName("A product cannot exclude itself, and an unknown product cannot be excluded")
    void anExclusionMustNameTwoRealProducts() throws Exception {
        long id = createProduct("Emergency Loan");

        as(alphaAdminToken, post(EXCLUSIONS), Map.of("loanTypeId", id, "excludedLoanTypeId", id))
                .andExpect(status().isBadRequest());

        as(alphaAdminToken, post(EXCLUSIONS), Map.of(
                "loanTypeId", id, "excludedLoanTypeId", id + 9_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.responseMessage").value("Not found."));

        assertThat(countWhere("organization_loan_type_exclusion")).isZero();
    }

    // ------------------------------------------------------------- the reason requirement

    @Test
    @DisplayName("A rate change submitted with no reason leaves the stored rate untouched")
    void aRateChangeWithNoReasonLeavesTheStoredRateUntouched() throws Exception {
        // Provision in its own committed transaction first. The write path provisions too, so
        // without this the rollback below would remove the row entirely and the assertion would be
        // about an absent row rather than an unchanged one.
        as(alphaAdminToken, get(LOAN)).andExpect(status().isOk());
        assertThat(storedLoanRate()).isEqualByComparingTo(ConfigProvisioner.DEFAULT_INTEREST_RATE);

        Map<String, Object> body = loanBody();
        body.remove("reason");

        as(alphaAdminToken, put(LOAN), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseCode").value("419"))
                .andExpect(jsonPath("$.responseMessage",
                        containsString("Changing a rate or a price requires a reason")))
                .andExpect(jsonPath("$.responseMessage", containsString("interestRate")));

        // The point of the test. The service had already applied the new rate to a managed entity
        // when the writer threw; only the rollback keeps the column at its old value.
        assertThat(storedLoanRate())
                .as("the refused rate must not have been saved")
                .isEqualByComparingTo(ConfigProvisioner.DEFAULT_INTEREST_RATE);
        assertThat(storedLoanMethod())
                .as("nor may any other field of the same submission survive")
                .isEqualTo(ConfigProvisioner.DEFAULT_INTEREST_METHOD.name());
        assertThat(countWhere("organization_config_audit")).isZero();
    }

    @Test
    @DisplayName("A share price change submitted with no reason leaves the stored price untouched")
    void aPriceChangeWithNoReasonLeavesTheStoredPriceUntouched() throws Exception {
        as(alphaAdminToken, get(SHARES)).andExpect(status().isOk());

        Map<String, Object> body = sharesBody();
        body.remove("reason");

        as(alphaAdminToken, put(SHARES), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseMessage", containsString("sharePrice")));

        assertThat(storedAmount("organization_shares_config", "share_price"))
                .isEqualByComparingTo(ConfigProvisioner.DEFAULT_SHARE_PRICE);
        assertThat(storedBoolean("organization_shares_config", "approval_required"))
                .isEqualTo(ConfigProvisioner.DEFAULT_APPROVAL_REQUIRED);
        assertThat(countWhere("organization_config_audit")).isZero();
    }

    @Test
    @DisplayName("A plan amount change submitted with no reason leaves the stored amount untouched")
    void aPlanAmountChangeWithNoReasonLeavesTheStoredAmountUntouched() throws Exception {
        long id = createPlan("Monthly Thrift");

        Map<String, Object> body = planBody("Monthly Thrift");
        body.put("planAmount", "9000.00");
        body.remove("reason");

        as(alphaAdminToken, put(SAVINGS_PLANS + "/{id}", id), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseMessage", containsString("planAmount")));

        assertThat(storedAmount("organization_savings_plan", "plan_amount"))
                .isEqualByComparingTo(new BigDecimal("5000.00"));
    }

    @Test
    @DisplayName("A product rate change submitted with no reason leaves the stored rate untouched")
    void aProductRateChangeWithNoReasonLeavesTheStoredRateUntouched() throws Exception {
        long id = createProduct("Emergency Loan");

        Map<String, Object> body = productBody("Emergency Loan");
        body.put("interestRate", "18.000");

        as(alphaAdminToken, put(LOAN_TYPES + "/{id}", id), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseMessage", containsString("interestRate")));

        assertThat(storedAmount("organization_loan_type", "interest_rate"))
                .isEqualByComparingTo(new BigDecimal("9.000"));
    }

    @Test
    @DisplayName("Naming a rate for the first time needs no reason, because nothing is changing")
    void aFirstTimeRateNeedsNoReason() throws Exception {
        // The create path records every field as null -> value, and a null old value is not a change
        // to anything: there is no previous decision to explain.
        as(alphaAdminToken, post(LOAN_TYPES), productBody("Emergency Loan"))
                .andExpect(status().isOk());

        assertThat(storedAmount("organization_loan_type", "interest_rate"))
                .isEqualByComparingTo(new BigDecimal("9.000"));
        assertThat(countWhere("organization_config_audit WHERE reason IS NULL")).isPositive();
    }

    @Test
    @DisplayName("A reason longer than the column is refused before anything is written")
    void anOverlongReasonIsRefused() throws Exception {
        long id = createProduct("Emergency Loan");
        long auditRowsBefore = countWhere("organization_config_audit");

        // The DELETE reason is a query parameter with no bean validation on it, so the writer's own
        // length guard is the only thing standing between it and a 512-character column.
        as(alphaAdminToken, delete(LOAN_TYPES + "/{id}", id)
                .param("reason", "x".repeat(513)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseMessage", containsString("Keep the reason under")));

        assertThat(storedBoolean("organization_loan_type", "active"))
                .as("the product must still be on offer")
                .isTrue();
        assertThat(countWhere("organization_config_audit")).isEqualTo(auditRowsBefore);
    }

    // ---------------------------------------------------------------------- the audit trail

    @Test
    @DisplayName("The trail records one row per changed setting, naming the administrator")
    void theTrailRecordsEveryChangedSetting() throws Exception {
        as(alphaAdminToken, get(LOAN)).andExpect(status().isOk());
        as(alphaAdminToken, put(LOAN), loanBody()).andExpect(status().isOk());

        // interestMethod NONE -> FLAT, interestRate 0 -> 5, requiredGuarantors 2 -> 1.
        assertThat(countWhere("organization_config_audit")).isEqualTo(3);
        for (String settingKey : new String[] {
                "interestMethod", "interestRate", "requiredGuarantors" }) {
            assertThat(countWhere("organization_config_audit WHERE setting_key = ?"
                    + " AND config_domain = ? AND organization_id = ? AND actor_user_id = ?"
                    + " AND actor_ledger_id = ? AND reason = ? AND effective_date = ?",
                    settingKey, "LOAN_CONFIG", alpha.organizationId(), alpha.admin().id(),
                    alpha.admin().ledgerID(), "Board resolution 2026/03",
                    LocalDate.now()))
                    .as("the trail must carry %s with its actor, reason and date", settingKey)
                    .isEqualTo(1);
        }

        as(alphaAdminToken, get(AUDIT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].configDomain").value("LOAN_CONFIG"))
                .andExpect(jsonPath("$[0].actorLedgerId").value(alpha.admin().ledgerID()));
    }

    @Test
    @DisplayName("Pressing save without changing anything records nothing")
    void anUnchangedSubmissionRecordsNothing() throws Exception {
        as(alphaAdminToken, get(LOAN)).andExpect(status().isOk());

        Map<String, Object> unchanged = new LinkedHashMap<>();
        unchanged.put("interestMethod", ConfigProvisioner.DEFAULT_INTEREST_METHOD.name());
        unchanged.put("interestRate", ConfigProvisioner.DEFAULT_INTEREST_RATE.toPlainString());
        unchanged.put("requiredGuarantors", ConfigProvisioner.DEFAULT_REQUIRED_GUARANTORS);

        // No reason, and none needed: resubmitting the same rate is not a rate change. If the diff
        // compared BigDecimals with equals, 0.000 against 0 would look like one and this would 400.
        as(alphaAdminToken, put(LOAN), unchanged).andExpect(status().isOk());

        assertThat(countWhere("organization_config_audit")).isZero();
    }

    @Test
    @DisplayName("The trail can be narrowed to one configuration area, and no further")
    void theTrailCanBeFilteredByDomain() throws Exception {
        as(alphaAdminToken, get(LOAN)).andExpect(status().isOk());
        as(alphaAdminToken, put(LOAN), loanBody()).andExpect(status().isOk());
        as(alphaAdminToken, get(SHARES)).andExpect(status().isOk());
        as(alphaAdminToken, put(SHARES), sharesBody()).andExpect(status().isOk());

        JsonNode loanOnly = read(as(alphaAdminToken, get(AUDIT).param("domain", "LOAN_CONFIG"))
                .andExpect(status().isOk()));
        assertThat(loanOnly).isNotEmpty();
        loanOnly.forEach(entry ->
                assertThat(entry.path("configDomain").asText()).isEqualTo("LOAN_CONFIG"));

        JsonNode everything = read(as(alphaAdminToken, get(AUDIT)).andExpect(status().isOk()));
        assertThat(everything.size()).isGreaterThan(loanOnly.size());

        as(alphaAdminToken, get(AUDIT).param("domain", "SOMETHING_ELSE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseMessage",
                        containsString("is not a configuration area")));
    }

    // -------------------------------------------------------------------- membership rules

    @Test
    @DisplayName("Automatic activation requires an ACTIVE starting status")
    void automaticActivationRequiresAnActiveStartingStatus() throws Exception {
        as(alphaAdminToken, get(MEMBERSHIP)).andExpect(status().isOk());

        Map<String, Object> contradiction = membershipBody();
        contradiction.put("autoActivateMembers", true);
        contradiction.put("defaultMemberStatus", "PENDING");

        as(alphaAdminToken, put(MEMBERSHIP), contradiction)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseMessage",
                        containsString("requires their starting status to be ACTIVE")));

        assertThat(storedBoolean("organization_membership_config", "auto_activate_members"))
                .isEqualTo(ConfigProvisioner.DEFAULT_AUTO_ACTIVATE_MEMBERS);

        // The consistent pair is accepted.
        Map<String, Object> consistent = membershipBody();
        consistent.put("autoActivateMembers", true);
        consistent.put("defaultMemberStatus", "ACTIVE");
        as(alphaAdminToken, put(MEMBERSHIP), consistent).andExpect(status().isOk());
    }

    @Test
    @DisplayName("An unrecognised starting status is refused")
    void anUnknownMemberStatusIsRefused() throws Exception {
        Map<String, Object> body = membershipBody();
        body.put("defaultMemberStatus", "SUSPENDED");

        as(alphaAdminToken, put(MEMBERSHIP), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseMessage", containsString("is not a member status")));
    }

    // --------------------------------------------------------------------- required fields

    @Test
    @DisplayName("A submission missing a required setting is refused with a readable message")
    void aMissingRequiredSettingIsRefused() throws Exception {
        Map<String, Object> withoutGuarantors = loanBody();
        withoutGuarantors.remove("requiredGuarantors");
        as(alphaAdminToken, put(LOAN), withoutGuarantors)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseCode").value("419"))
                .andExpect(jsonPath("$.responseMessage").isNotEmpty());

        Map<String, Object> withoutPrice = sharesBody();
        withoutPrice.remove("sharePrice");
        as(alphaAdminToken, put(SHARES), withoutPrice).andExpect(status().isBadRequest());

        Map<String, Object> withoutName = productBody("");
        as(alphaAdminToken, post(LOAN_TYPES), withoutName).andExpect(status().isBadRequest());

        assertThat(countWhere("organization_loan_type")).isZero();
    }

    // ---------------------------------------------------------------------------- bodies

    private Map<String, Object> loanBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("interestMethod", "FLAT");
        body.put("interestRate", "5.000");
        body.put("requiredGuarantors", 1);
        body.put("reason", "Board resolution 2026/03");
        return body;
    }

    private Map<String, Object> sharesBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sharePrice", "250.00");
        body.put("approvalRequired", false);
        body.put("withdrawalAllowed", false);
        body.put("reason", "Share price review 2026");
        return body;
    }

    private Map<String, Object> repaymentBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("allowPartialRepayment", true);
        body.put("allowOverpayment", true);
        body.put("settlementTolerance", "1.50");
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
        body.put("defaultMemberStatus", "NEW");
        return body;
    }

    private Map<String, Object> productBody(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("description", "For urgent member needs.");
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
        body.put("description", "Regular monthly contribution.");
        body.put("planAmount", "5000.00");
        body.put("frequency", "MONTHLY");
        return body;
    }

    // --------------------------------------------------------------------------- helpers

    private long createProduct(String name) throws Exception {
        return read(as(alphaAdminToken, post(LOAN_TYPES), productBody(name))
                .andExpect(status().isOk())).path("id").asLong();
    }

    private long createPlan(String name) throws Exception {
        return read(as(alphaAdminToken, post(SAVINGS_PLANS), planBody(name))
                .andExpect(status().isOk())).path("id").asLong();
    }

    private BigDecimal storedLoanRate() {
        return storedAmount("organization_loan_config", "interest_rate");
    }

    private String storedLoanMethod() {
        return jdbcTemplate.queryForObject(
                "SELECT interest_method FROM organization_loan_config WHERE organization_id = ?",
                String.class, alpha.organizationId());
    }

    private BigDecimal storedAmount(String table, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE organization_id = ?",
                BigDecimal.class, alpha.organizationId());
    }

    private Boolean storedBoolean(String table, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE organization_id = ?",
                Boolean.class, alpha.organizationId());
    }

    private JsonNode read(ResultActions performed) throws Exception {
        return objectMapper.readTree(performed.andReturn().getResponse().getContentAsString());
    }
}
