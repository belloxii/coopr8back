package com.invo.coopr8.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.invo.coopr8.support.AbstractTwoTenantTest;

/**
 * Guards the endpoints that hand a JPA entity straight to Jackson against the lazy association
 * that has to be loaded before the session closes.
 *
 * <p><strong>Why this class exists separately from the isolation tests.</strong> Five of them went
 * red at once with {@code HttpMessageNotWritableException} wrapping
 * {@code failed to lazily initialize a collection of role: com.invo.coopr8.model.Loan.repays},
 * because {@code Loan.repays} is a LAZY {@code @OneToMany} that {@code @JsonManagedReference} tells
 * Jackson to serialize. Those tests are about tenant boundaries; they detected this defect by
 * accident, and if the fix regresses they would go red again for a reason that reads like an
 * isolation failure. The assertions here name the actual contract instead.
 *
 * <p><strong>Why it is not covered by the existing fixture.</strong> {@code TenantFixture} seeds
 * exactly one repayment per loan, so a collection join produces one row per loan and duplicate
 * roots can never appear. This class adds a second repayment first, which is what makes the
 * {@code $.length()} assertions below meaningful: a collection {@code JOIN FETCH} yields one result
 * row per child, and a list endpoint that returned the same loan twice would be a regression the
 * fixture cannot see. Hibernate 6 de-duplicates root entities for exactly this case -- this pins
 * that behaviour rather than trusting it.
 *
 * <p>Every assertion also reads {@code user}, which is a separate guard: the fix uses
 * {@code EntityGraphType.LOAD}, and under the {@code FETCH} default JPA treats attributes absent
 * from the graph as LAZY -- which would demote the EAGER {@code user} and move the same failure one
 * field sideways, somewhere no existing test looks.
 */
class LazyAssociationSerializationTest extends AbstractTwoTenantTest {

    /** Alpha's loan, with a second repayment so a collection join can duplicate its root. */
    private long secondRepayId;

    @BeforeEach
    void addASecondRepaymentToAlphasLoan() {
        secondRepayId = jdbcTemplate.queryForObject("""
                INSERT INTO repay
                    (organization_id, user_id, loan_id, amount, balance, channel, loan_type,
                     status, txn_id, created_at)
                VALUES (?, ?, ?, ?, ?, 'manual', 'normal', 'success', ?, now())
                RETURNING id
                """,
                Long.class,
                alpha.organizationId(), alpha.member().id(), alpha.loanId(),
                new BigDecimal("10000.00"), new BigDecimal("100000.00"),
                "txn-repay-second-" + alpha.slug());

        assertThat(countWhere("repay WHERE loan_id = ?", alpha.loanId()))
                .as("the point of this class is a loan with more than one repayment")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("A single loan serializes its repayments and its member")
    void aSingleLoanSerializesItsRepaymentsAndItsMember() throws Exception {
        as(alphaMemberToken, get("/api/loan/{id}", alpha.loanId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alpha.loanId()))
                // The collection the frontend's loan detail screen renders.
                .andExpect(jsonPath("$.repays.length()").value(2))
                .andExpect(jsonPath("$.repays[?(@.id == " + secondRepayId + ")]").exists())
                // Still EAGER under the LOAD graph, and one level deeper than the collection:
                // each repayment carries its own member, so this is the nested lazy path too.
                .andExpect(jsonPath("$.user.id").value(alpha.member().id()))
                .andExpect(jsonPath("$.repays[0].user.id").value(alpha.member().id()));
    }

    @Test
    @DisplayName("Loan list endpoints return one row per loan, not one per repayment")
    void loanListEndpointsReturnOneRowPerLoanNotOnePerRepayment() throws Exception {
        // The whole-cooperative list and the per-member list are separate finders, and the
        // guarantor list is a third -- a hand-written @Query, so it is the one most easily missed.
        assertLoanListIsOneRowWithBothRepayments(alphaAdminToken, "/api/admin/loan/all");
        assertLoanListIsOneRowWithBothRepayments(alphaAdminToken,
                "/api/admin/loan/loansbyuserid/" + alpha.member().id());
        assertLoanListIsOneRowWithBothRepayments(alphaMemberToken, "/api/loan/myloans");

        // Alpha's administrator is guarantor1 on the member's loan; see TenantFixture.
        assertLoanListIsOneRowWithBothRepayments(alphaAdminToken, "/api/guarantor/myrequests");
    }

    @Test
    @DisplayName("Approving a share withdrawal reaches the cooperative and commits")
    void approvingAShareWithdrawalReachesTheCooperativeAndCommits() throws Exception {
        // The approval path resolves the owning cooperative through a LAZY @ManyToOne on the
        // member, which failed with "could not initialize proxy [Organization#n] - no Session"
        // before the service method had a transaction of its own.
        as(alphaAdminToken, put("/api/shares/{id}/approve", alpha.shareId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCode").value("100"));

        // The transaction that fixed the proxy also moved the commit to the end of the method, so
        // assert the row actually changed rather than only that the response said so.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM shares WHERE id = ?", String.class, alpha.shareId()))
                .as("the approval must be committed, not rolled back at the end of the method")
                .isEqualTo("approved");

        // Matched on type, not reference_id: both tables restart at identity 1 after the
        // truncate, so the loan and the share share an id and the fixture's own
        // LOAN_APPROVED notification already points at reference_id 1.
        assertThat(countWhere("notification WHERE type = ? AND organization_id = ?",
                "SHARES_APPROVED", alpha.organizationId()))
                .as("the member is told, in the same transaction as the approval")
                .isEqualTo(1);
    }

    private void assertLoanListIsOneRowWithBothRepayments(String token, String uri)
            throws Exception {
        as(token, get(uri))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(alpha.loanId()))
                .andExpect(jsonPath("$[0].repays.length()").value(2))
                .andExpect(jsonPath("$[0].user.id").value(alpha.member().id()));
    }
}
