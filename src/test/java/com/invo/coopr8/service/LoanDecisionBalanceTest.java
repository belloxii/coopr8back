package com.invo.coopr8.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.invo.coopr8.dto.LoanDto;

/**
 * Stage 0, item 2 -- phantom debt created by rejecting a loan application.
 *
 * <p><strong>The defect.</strong> {@code rejectLoan} did this:
 *
 * <pre>{@code user.setLoanBalance(user.getLoanBalance().add(applyLoan.getAmount()));}</pre>
 *
 * <p>Declining an application charged the member the full amount they had asked for. The money was
 * never disbursed, the loan was marked {@code declined} so no repayment schedule existed, and
 * nothing anywhere reversed it -- the debt simply stayed on the member's record. Every rejected
 * application made a member permanently poorer on paper, and it compounded: three rejected
 * ₦500,000 applications left ₦1,500,000 of debt against no loan at all.
 *
 * <p><strong>Where the obligation belongs.</strong> Exactly one of the three transitions creates
 * debt, and it is the one where money changes hands:
 *
 * <ul>
 *   <li>applying records a request -- {@code balance} zero, no schedule, no obligation;</li>
 *   <li>approving disburses the principal and is therefore where {@code loanBalance} grows;</li>
 *   <li>rejecting closes the request and must leave the member's balance exactly as it found it.</li>
 * </ul>
 *
 * <p>All three are asserted here, because a fix that merely deleted the offending line would still
 * pass a test that only looked at rejection.
 */
class LoanDecisionBalanceTest extends AbstractLoanFinancialTest {

    private static final String PRINCIPAL = "500000.00";

    @Test
    @DisplayName("rejecting an application does not increase the member's loan balance")
    void rejectingAnApplicationDoesNotCreateDebt() throws Exception {
        long loanId = submittedLoan(alpha, "reject-a", PRINCIPAL, 10);
        BigDecimal balanceBefore = memberLoanBalance(alpha);

        reject(alphaAdminToken, loanId, "Insufficient savings history.")
                .andExpect(status().isOk());

        assertThat(memberLoanBalance(alpha))
                .as("a declined application must never be charged to the member")
                .isEqualByComparingTo(balanceBefore);
        assertThat(loanStatus(loanId))
                .as("the rejection status the application already used is preserved")
                .isEqualTo("declined");
        assertThat(loanBalance(loanId))
                .as("a declined loan has no outstanding balance of its own")
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("rejection still records the remark, the notification and the email")
    void rejectionSideEffectsArePreserved() throws Exception {
        long loanId = submittedLoan(alpha, "reject-b", PRINCIPAL, 10);

        reject(alphaAdminToken, loanId, "Guarantor withdrew.").andExpect(status().isOk());

        assertThat(loanRemark(loanId)).isEqualTo("Guarantor withdrew.");
        assertThat(countWhere(
                "notification WHERE reference_id = ? AND type = 'LOAN_DECLINED' "
                        + "AND organization_id = ?", loanId, alpha.organizationId()))
                .as("the member is still told their application was declined")
                .isEqualTo(1);
        assertThat(recordedEmails.sentTo(alpha.member().email()))
                .as("the decline email is still sent")
                .anySatisfy(email -> assertThat(email.subject()).isEqualTo("Loan Declined"));
    }

    @Test
    @DisplayName("rejecting several applications leaves the balance untouched every time")
    void repeatedRejectionsDoNotAccumulateDebt() throws Exception {
        BigDecimal balanceBefore = memberLoanBalance(alpha);

        for (int attempt = 1; attempt <= 3; attempt++) {
            long loanId = submittedLoan(alpha, "reject-repeat-" + attempt, PRINCIPAL, 10);
            reject(alphaAdminToken, loanId, "Declined attempt " + attempt)
                    .andExpect(status().isOk());
        }

        assertThat(memberLoanBalance(alpha))
                .as("three declined ₦500,000 applications must not add ₦1,500,000 of debt")
                .isEqualByComparingTo(balanceBefore);
    }

    @Test
    @DisplayName("applying for a loan does not create debt")
    void applyingForALoanDoesNotCreateDebt() throws Exception {
        long secondCooperator = extraMember(alpha, 2, "second-cooperator", "08019002");
        assertThat(secondCooperator).isPositive();
        BigDecimal balanceBefore = memberLoanBalance(alpha);

        as(alphaMemberToken, post("/api/loan/apply"), LoanDto.builder()
                        .amount(new BigDecimal(PRINCIPAL))
                        .type("apply-no-debt")
                        .purpose("School fees")
                        .duration(10)
                        .accountDetails("0123456789 / Test Bank")
                        .guarantor1(alpha.admin().phone())
                        .guarantor2("08019002")
                        .build())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCode").value("100"));

        assertThat(memberLoanBalance(alpha))
                .as("an application is a request, not a disbursement")
                .isEqualByComparingTo(balanceBefore);
        assertThat(countWhere("loan WHERE type = ? AND status = 'submitted' AND user_id = ?",
                "apply-no-debt", alpha.member().id()))
                .as("the application itself was recorded")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("approving a loan is what creates the principal obligation")
    void approvingALoanCreatesTheObligation() throws Exception {
        long loanId = submittedLoan(alpha, "approve-creates-debt", PRINCIPAL, 10);
        BigDecimal balanceBefore = memberLoanBalance(alpha);

        approve(alphaAdminToken, loanId).andExpect(status().isOk());

        assertThat(memberLoanBalance(alpha))
                .as("approval, and only approval, adds the principal")
                .isEqualByComparingTo(balanceBefore.add(new BigDecimal(PRINCIPAL)));
        assertThat(loanStatus(loanId)).isEqualTo("approved");
        assertThat(loanBalance(loanId)).isEqualByComparingTo(PRINCIPAL);
    }
}
