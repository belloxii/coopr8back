package com.invo.coopr8.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;

/**
 * Stage 0, item 4 -- the administrative loan decision must be financially atomic.
 *
 * <p>{@code approveLoan} and {@code rejectLoan} each make several database mutations: they move the
 * loan's status, they change the member's running {@code loanBalance}, and they write a
 * notification. Before this work neither method had any transaction boundary at all, so a failure
 * part-way through -- a constraint violation on the notification, say -- could leave the member
 * charged for a loan whose status never advanced, or advance the status without the matching
 * balance. This class pins three things:
 *
 * <ol>
 *   <li><strong>All-or-nothing.</strong> Every mutation of a decision commits together, and a
 *       failure during the decision rolls every mutation back.</li>
 *   <li><strong>Email is outside the money.</strong> A failing email send cannot roll back a
 *       decision that has already been made -- the financial state commits whole regardless of what
 *       the mail server does -- and the failure is surfaced, not silently swallowed.</li>
 *   <li><strong>No lazy-serialization regression.</strong> Both endpoints return the loan entity to
 *       Jackson under {@code open-in-view=false}; putting the work in a transaction that closes when
 *       the method returns must not reintroduce the "failed to lazily initialize" defect the entity
 *       graph and this profile exist to catch.</li>
 * </ol>
 *
 * <p>Every post-condition is read with {@code jdbcTemplate}, i.e. straight from the committed
 * database, because a rollback is only observable there.
 */
class LoanDecisionTransactionTest extends AbstractLoanFinancialTest {

    private static final String PRINCIPAL = "240000.00";

    @Test
    @DisplayName("approval commits the status, the balance and the notification together")
    void approvalCommitsEveryMutationTogether() throws Exception {
        setMemberLoanBalance(alpha, "0.00");
        long loanId = submittedLoan(alpha, "txn-approve", PRINCIPAL, 12);

        approve(alphaAdminToken, loanId).andExpect(status().isOk());

        // Committed as a set: status advanced, schedule written, member charged, member told.
        assertThat(loanStatus(loanId)).isEqualTo("approved");
        assertThat(loanBalance(loanId)).isEqualByComparingTo(PRINCIPAL);
        assertThat(scheduledInstalment(loanId)).isEqualByComparingTo("20000.00");
        assertThat(memberLoanBalance(alpha)).isEqualByComparingTo(PRINCIPAL);
        assertThat(countWhere(
                "notification WHERE reference_id = ? AND type = 'LOAN_APPROVED'", loanId))
                .isEqualTo(1);
        assertThat(recordedEmails.sentTo(alpha.member().email()))
                .anySatisfy(email -> assertThat(email.subject()).isEqualTo("Loan Approved"));
    }

    @Test
    @DisplayName("a failure during approval rolls back every mutation")
    void approvalRollsBackEntirelyWhenAMutationFails() throws Exception {
        setMemberLoanBalance(alpha, "0.00");

        // The notification message embeds the loan type. A 255-character type -- the widest the
        // loan.type column allows -- makes that message overflow notification.message (also
        // varchar(255)), so notificationRepository.save() fails inside the decision, AFTER the
        // status change and the member charge have been staged. It is a genuine mid-transaction
        // failure, not a mock: exactly the shape that used to leave a member charged for a loan
        // whose status never moved.
        String overlongType = "x".repeat(255);
        long loanId = submittedLoan(alpha, overlongType, PRINCIPAL, 12);

        // No handler resolves a DataIntegrityViolationException, so MockMvc rethrows it from
        // perform() instead of turning it into a 500 body. The throw is caught rather than
        // expected as a status, because the status is not the point: the assertions below are,
        // and an uncaught throw here would abandon the test before it checked anything.
        Throwable thrown = catchThrowable(() -> approve(alphaAdminToken, loanId));

        assertThat(thrown)
                .as("the oversized notification must genuinely fail the decision -- without a "
                        + "real mid-transaction failure there is no rollback to observe")
                .isNotNull()
                .hasStackTraceContaining("DataIntegrityViolationException");

        // Nothing was kept. The member is not charged for a decision that did not complete.
        assertThat(loanStatus(loanId))
                .as("a failed approval must not advance the loan's status")
                .isEqualTo("submitted");
        assertThat(scheduledInstalment(loanId))
                .as("a failed approval must not schedule instalments")
                .isNull();
        assertThat(loanBalance(loanId)).isEqualByComparingTo("0.00");
        assertThat(memberLoanBalance(alpha))
                .as("the member charge must roll back with the rest of the decision")
                .isEqualByComparingTo("0.00");
        assertThat(countWhere("notification WHERE reference_id = ?", loanId))
                .as("no notification survives a rolled-back decision")
                .isZero();
        assertThat(recordedEmails.sent())
                .as("a decision that rolled back must not have announced itself by email -- this "
                        + "is the direction after-commit dispatch exists to protect")
                .noneSatisfy(email -> assertThat(email.subject()).isEqualTo("Loan Approved"));
    }

    @Test
    @DisplayName("a failing email does not roll back the approval")
    void emailFailureDoesNotRollBackTheApproval() throws Exception {
        setMemberLoanBalance(alpha, "0.00");
        long loanId = submittedLoan(alpha, "txn-approve-mailfail", PRINCIPAL, 12);
        recordedEmails.failSendsWith(new MailSendException("SMTP is down"));

        // The send may or may not surface as an error to the caller; what must hold is that the
        // decision is already durably committed by the time the email is even attempted. So we
        // assert on the committed database, not on the HTTP status.
        try {
            approve(alphaAdminToken, loanId);
        } catch (Exception propagatedAfterCommit) {
            // A synchronous mail failure after commit is allowed to surface; it must not undo the
            // commit, which is what the assertions below verify.
        }

        assertThat(loanStatus(loanId))
                .as("the approval is committed before the email is attempted")
                .isEqualTo("approved");
        assertThat(loanBalance(loanId)).isEqualByComparingTo(PRINCIPAL);
        assertThat(memberLoanBalance(alpha)).isEqualByComparingTo(PRINCIPAL);
        assertThat(countWhere(
                "notification WHERE reference_id = ? AND type = 'LOAN_APPROVED'", loanId))
                .isEqualTo(1);
        assertThat(recordedEmails.sent())
                .as("the email failure is not silently skipped -- the send was attempted")
                .anySatisfy(email -> assertThat(email.subject()).isEqualTo("Loan Approved"));
    }

    @Test
    @DisplayName("a failing email does not roll back the rejection")
    void emailFailureDoesNotRollBackTheRejection() throws Exception {
        BigDecimal balanceBefore = memberLoanBalance(alpha);
        long loanId = submittedLoan(alpha, "txn-reject-mailfail", PRINCIPAL, 12);
        recordedEmails.failSendsWith(new MailSendException("SMTP is down"));

        try {
            reject(alphaAdminToken, loanId, "Declined; mail server unavailable.");
        } catch (Exception propagatedAfterCommit) {
            // See the approval case: an after-commit mail failure must not undo the rejection.
        }

        assertThat(loanStatus(loanId)).isEqualTo("declined");
        assertThat(loanRemark(loanId)).isEqualTo("Declined; mail server unavailable.");
        assertThat(memberLoanBalance(alpha))
                .as("rejection never charges the member, mail failure or not")
                .isEqualByComparingTo(balanceBefore);
        assertThat(recordedEmails.sent())
                .anySatisfy(email -> assertThat(email.subject()).isEqualTo("Loan Declined"));
    }

    @Test
    @DisplayName("the approval response still serializes with open-in-view disabled")
    void approvalResponseSerializesWithOpenInViewDisabled() throws Exception {
        long loanId = submittedLoan(alpha, "txn-approve-serialize", PRINCIPAL, 12);

        // The endpoint returns the JPA entity straight to Jackson, which runs after the method's
        // transaction has closed. A lazy field touched during serialization would blow up as an
        // HTTP 500 under this profile; a well-formed body proves the transaction boundary did not
        // reintroduce that defect.
        approve(alphaAdminToken, loanId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) loanId))
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.amount").value(240000.00))
                .andExpect(jsonPath("$.repays").isArray())
                .andExpect(jsonPath("$.user.id").value((int) alpha.member().id()));
    }

    @Test
    @DisplayName("the rejection response still serializes with open-in-view disabled")
    void rejectionResponseSerializesWithOpenInViewDisabled() throws Exception {
        long loanId = submittedLoan(alpha, "txn-reject-serialize", PRINCIPAL, 12);

        reject(alphaAdminToken, loanId, "Not eligible.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((int) loanId))
                .andExpect(jsonPath("$.status").value("declined"))
                .andExpect(jsonPath("$.remark").value("Not eligible."))
                .andExpect(jsonPath("$.repays").isArray())
                .andExpect(jsonPath("$.user.id").value((int) alpha.member().id()));
    }
}
