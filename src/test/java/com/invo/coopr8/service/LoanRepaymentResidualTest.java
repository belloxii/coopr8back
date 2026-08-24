package com.invo.coopr8.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stage 0, item 1 -- the repayment deadlock caused by a rounded instalment.
 *
 * <p><strong>The defect.</strong> Approval divides the principal into {@code duration} instalments
 * and rounds the result to the kobo:
 *
 * <pre>{@code repayAmount = amount.divide(duration, 2, HALF_UP)}</pre>
 *
 * <p>and repayment then insisted that every payment be an exact multiple of that figure. Those two
 * rules are only compatible when the division is exact. ₦100,000 over three months schedules
 * ₦33,333.33; three of those come to ₦99,999.99, so the loan settles at ₦0.01 outstanding -- and
 * ₦0.01 is not a multiple of ₦33,333.33, so it could never be paid. The loan could not be closed,
 * the member's {@code loanBalance} could not return to zero, and no amount of money could fix it.
 *
 * <p><strong>The rule these tests encode</strong> (Phase 4 Decision 3): the final instalment
 * absorbs the residual, computed as {@code total_repayable - sum(previous instalments)} -- never as
 * {@code rounded_instalment + residual}, which could exceed the agreed total. Because the loan's
 * outstanding {@code balance} is maintained as exactly that subtraction, the rule reduces to a
 * single invariant worth stating plainly:
 *
 * <blockquote>The whole outstanding balance is always a payable amount.</blockquote>
 *
 * <p>That closes the deadlock from both ends -- the member can pay the reduced final instalment, or
 * the stranded kobo left behind by paying the rounded figure to the last month -- while the stored
 * total is never adjusted to make the division convenient, and no payment may exceed what is owed.
 *
 * <p>No interest is involved anywhere here. {@code total_repayable} is the principal, because that
 * is all the platform charges today; Stage 4 is where that stops being true.
 */
class LoanRepaymentResidualTest extends AbstractLoanFinancialTest {

    /** ₦100,000 over 3 months: 33,333.333... to the kobo, the arithmetic that deadlocked. */
    private static final String UNEVEN_PRINCIPAL = "100000.00";
    private static final int UNEVEN_TENURE = 3;
    private static final String SCHEDULED_INSTALMENT = "33333.33";

    @Test
    @DisplayName("approval keeps the principal authoritative and schedules the rounded instalment")
    void approvalSchedulesTheRoundedInstalmentWithoutAdjustingTheTotal() throws Exception {
        setMemberLoanBalance(alpha, "0.00");
        long loanId = submittedLoan(alpha, "residual-a", UNEVEN_PRINCIPAL, UNEVEN_TENURE);

        approve(alphaAdminToken, loanId).andExpect(status().isOk());

        // The total owed is the principal, unrounded and unadjusted. Nudging it to 99,999.99 or
        // 100,000.02 would make the division come out evenly and would also mean the cooperative
        // and the member disagree about the debt.
        assertThat(loanAmount(loanId))
                .as("the stored total repayable is authoritative")
                .isEqualByComparingTo(UNEVEN_PRINCIPAL);
        assertThat(loanBalance(loanId)).isEqualByComparingTo(UNEVEN_PRINCIPAL);
        assertThat(scheduledInstalment(loanId))
                .as("instalments are the rounded per-month figure")
                .isEqualByComparingTo(SCHEDULED_INSTALMENT);
        assertThat(memberLoanBalance(alpha))
                .as("approval -- and only approval -- creates the obligation")
                .isEqualByComparingTo(UNEVEN_PRINCIPAL);
    }

    @Test
    @DisplayName("the final instalment absorbs the residual and closes the loan at exactly zero")
    void theFinalInstalmentAbsorbsTheResidual() throws Exception {
        setMemberLoanBalance(alpha, "0.00");
        long loanId = submittedLoan(alpha, "residual-b", UNEVEN_PRINCIPAL, UNEVEN_TENURE);
        approve(alphaAdminToken, loanId).andExpect(status().isOk());

        payInstalment(loanId, SCHEDULED_INSTALMENT);
        assertThat(loanBalance(loanId)).isEqualByComparingTo("66666.67");

        payInstalment(loanId, SCHEDULED_INSTALMENT);
        assertThat(loanBalance(loanId)).isEqualByComparingTo("33333.34");

        // total - sum(previous) = 100,000.00 - 66,666.66. One kobo more than the scheduled
        // instalment, and exactly what is outstanding: the residual lands here, on the last
        // payment, rather than being stranded after it.
        payInstalment(loanId, "33333.34");

        assertThat(loanBalance(loanId))
                .as("a loan of ₦100,000 over 3 months must be able to reach exactly ₦0.00")
                .isEqualByComparingTo("0.00");
        assertThat(loanStatus(loanId)).isEqualTo("completed");
        assertThat(memberLoanBalance(alpha))
                .as("the member's loan balance must reach exactly zero")
                .isEqualByComparingTo("0.00");
        assertThat(totalRepaid(loanId))
                .as("the payments must add up to the agreed total, not to more and not to less")
                .isEqualByComparingTo(UNEVEN_PRINCIPAL);
        assertThat(repaymentCount(loanId)).isEqualTo(UNEVEN_TENURE);
    }

    @Test
    @DisplayName("the stranded kobo is payable instead of deadlocking the loan")
    void theResidualLeftByPayingTheRoundedFigureThroughoutIsStillPayable() throws Exception {
        setMemberLoanBalance(alpha, "0.00");
        long loanId = submittedLoan(alpha, "residual-c", UNEVEN_PRINCIPAL, UNEVEN_TENURE);
        approve(alphaAdminToken, loanId).andExpect(status().isOk());

        // A member who pays the figure on the schedule, every month, for the whole tenure.
        for (int month = 1; month <= UNEVEN_TENURE; month++) {
            payInstalment(loanId, SCHEDULED_INSTALMENT);
        }

        // This is the state the loan used to be stuck in forever.
        assertThat(loanBalance(loanId)).isEqualByComparingTo("0.01");
        assertThat(loanStatus(loanId)).isEqualTo("approved");

        payInstalment(loanId, "0.01");

        assertThat(loanBalance(loanId))
                .as("₦0.01 outstanding must be payable, not a permanent state")
                .isEqualByComparingTo("0.00");
        assertThat(loanStatus(loanId)).isEqualTo("completed");
        assertThat(memberLoanBalance(alpha)).isEqualByComparingTo("0.00");
        assertThat(totalRepaid(loanId)).isEqualByComparingTo(UNEVEN_PRINCIPAL);
    }

    @Test
    @DisplayName("the whole outstanding balance may be settled in one payment")
    void theEntireBalanceMayBeSettledAtOnce() throws Exception {
        setMemberLoanBalance(alpha, "0.00");
        long loanId = submittedLoan(alpha, "residual-d", UNEVEN_PRINCIPAL, UNEVEN_TENURE);
        approve(alphaAdminToken, loanId).andExpect(status().isOk());

        // ₦100,000.00 is not a multiple of ₦33,333.33. It is, however, exactly what is owed.
        payInstalment(loanId, UNEVEN_PRINCIPAL);

        assertThat(loanBalance(loanId)).isEqualByComparingTo("0.00");
        assertThat(loanStatus(loanId)).isEqualTo("completed");
        assertThat(memberLoanBalance(alpha)).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a final instalment may not exceed the outstanding balance")
    void aPaymentLargerThanTheOutstandingBalanceIsRefused() throws Exception {
        setMemberLoanBalance(alpha, "0.00");
        long loanId = submittedLoan(alpha, "residual-e", UNEVEN_PRINCIPAL, UNEVEN_TENURE);
        approve(alphaAdminToken, loanId).andExpect(status().isOk());

        payInstalment(loanId, SCHEDULED_INSTALMENT);
        payInstalment(loanId, SCHEDULED_INSTALMENT);
        assertThat(loanBalance(loanId)).isEqualByComparingTo("33333.34");

        // One kobo more than is owed. Absorbing the residual must not become a licence to
        // overpay: the ceiling is the outstanding balance, in both directions.
        repay(alphaMemberToken, loanId, "33333.35")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseCode").value("419"));

        assertThat(loanBalance(loanId))
                .as("a refused payment must leave the loan untouched")
                .isEqualByComparingTo("33333.34");
        assertThat(loanStatus(loanId)).isEqualTo("approved");
        assertThat(repaymentCount(loanId)).isEqualTo(2);
        assertThat(memberLoanBalance(alpha)).isEqualByComparingTo("33333.34");
    }

    @Test
    @DisplayName("an amount that is neither an instalment nor the full balance is still refused")
    void anArbitraryPartialPaymentIsStillRefused() throws Exception {
        setMemberLoanBalance(alpha, "0.00");
        long loanId = submittedLoan(alpha, "residual-f", UNEVEN_PRINCIPAL, UNEVEN_TENURE);
        approve(alphaAdminToken, loanId).andExpect(status().isOk());

        // Well under the balance, so nothing to do with the overpayment rule, and not a whole
        // number of instalments. Letting the last payment absorb the residual must not turn the
        // repayment plan into "pay what you like".
        repay(alphaMemberToken, loanId, "5000.00")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseCode").value("419"));

        assertThat(loanBalance(loanId)).isEqualByComparingTo(UNEVEN_PRINCIPAL);
        assertThat(repaymentCount(loanId)).isZero();
        assertThat(memberLoanBalance(alpha)).isEqualByComparingTo(UNEVEN_PRINCIPAL);
    }

    @Test
    @DisplayName("ordinary repayment of an evenly divisible loan is unchanged")
    void evenlyDivisibleRepaymentBehaviourIsPreserved() throws Exception {
        setMemberLoanBalance(alpha, "0.00");
        long loanId = submittedLoan(alpha, "residual-g", "120000.00", 12);
        approve(alphaAdminToken, loanId).andExpect(status().isOk());

        assertThat(scheduledInstalment(loanId)).isEqualByComparingTo("10000.00");

        payInstalment(loanId, "10000.00");
        assertThat(loanBalance(loanId)).isEqualByComparingTo("110000.00");
        assertThat(memberLoanBalance(alpha)).isEqualByComparingTo("110000.00");

        // Several months at once, which the multiples rule has always allowed.
        payInstalment(loanId, "20000.00");
        assertThat(loanBalance(loanId)).isEqualByComparingTo("90000.00");
        assertThat(loanStatus(loanId))
                .as("a part-paid loan is not complete")
                .isEqualTo("approved");

        repay(alphaMemberToken, loanId, "7500.00")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseCode").value("419"));
        repay(alphaMemberToken, loanId, "200000.00")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.responseCode").value("419"));

        assertThat(loanBalance(loanId)).isEqualByComparingTo("90000.00");
        assertThat(repaymentCount(loanId)).isEqualTo(2);
    }

    /** Pays and asserts the payment was accepted, so a failure is reported where it happened. */
    private void payInstalment(long loanId, String amount) throws Exception {
        BigDecimal before = loanBalance(loanId);

        repay(alphaMemberToken, loanId, amount)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCode").value("100"));

        assertThat(loanBalance(loanId))
                .as("paying ₦%s against a balance of ₦%s must reduce the balance by exactly that",
                        amount, before)
                .isEqualByComparingTo(before.subtract(new BigDecimal(amount)));
    }
}
