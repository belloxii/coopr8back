package com.invo.coopr8.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.springframework.test.web.servlet.ResultActions;

import com.invo.coopr8.dto.LoanDto;
import com.invo.coopr8.dto.RepayDto;
import com.invo.coopr8.support.AbstractTwoTenantTest;
import com.invo.coopr8.support.TenantFixture;
import com.invo.coopr8.support.TenantFixture.Tenant;
import com.invo.coopr8.utils.LedgerIDGen;

/**
 * Shared scaffolding for the Stage 0 financial-correctness tests: loan approval, rejection and
 * repayment, driven through the real HTTP endpoints and read back with plain SQL.
 *
 * <p><strong>Why the reads go through {@code jdbcTemplate} rather than the repositories.</strong>
 * These tests are about what was <em>committed</em>. A repository read inside the test would share
 * the application's persistence context and its tenant filter, so a row that only exists in a
 * flushed-but-rolled-back transaction, or a value that only exists in memory on a detached entity,
 * could still be observed. {@code SELECT} against the container is the only account of the database
 * that a rollback can actually change.
 *
 * <p><strong>Why loans are inserted rather than applied for.</strong> {@code POST /api/loan/apply}
 * enforces one loan per type, refuses combinations of product types and resolves both guarantors by
 * phone number. None of that is under test here, and all of it would have to be satisfied before
 * the arithmetic could be reached. Inserting the row that {@code applyLoan} would have written --
 * {@code status = 'submitted'}, zero balance, no repayment plan yet -- puts the loan in exactly the
 * state an administrator decides on, with nothing else in the way.
 */
abstract class AbstractLoanFinancialTest extends AbstractTwoTenantTest {

    /**
     * Inserts the loan {@code applyLoan} would have created: submitted, no balance, and
     * <em>no</em> repayment plan. {@code repay_amount} is left NULL on purpose, because
     * {@code applyLoan} never sets it -- approval is what schedules the instalments, and a test
     * that pre-filled the column could not tell whether approval had done its job.
     */
    protected long submittedLoan(Tenant tenant, String type, String amount, int duration) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO loan
                    (organization_id, user_id, guarantor1_id, amount, balance, repay_amount,
                     duration, installments_paid, guarantor1status, guarantor2status,
                     purpose, status, type, created_at)
                VALUES (?, ?, ?, ?, 0.00, NULL, ?, 0, 'APPROVED', 'APPROVED', ?, 'submitted', ?,
                        now())
                RETURNING id
                """, Long.class,
                tenant.organizationId(), tenant.member().id(), tenant.admin().id(),
                new BigDecimal(amount), duration, "Stage 0 regression loan", type);

        assertThat(id).as("the regression loan must have been inserted").isNotNull();
        return id;
    }

    /**
     * Sets the member's running loan balance.
     *
     * <p>The fixture seeds ₦110,000.00, which has nothing to do with the loans these tests create.
     * Any assertion that the balance "reaches exactly zero" is only meaningful once the member's
     * only debt is the loan under test, so the tests that make that claim start from a clean zero.
     */
    protected void setMemberLoanBalance(Tenant tenant, String balance) {
        jdbcTemplate.update("UPDATE users SET loan_balance = ? WHERE id = ?",
                new BigDecimal(balance), tenant.member().id());
    }

    /**
     * Inserts a second ordinary member in {@code tenant}, so a test can name two distinct
     * guarantors.
     *
     * <p>The fixture seeds only one administrator and one member, and {@code applyLoan} refuses an
     * application whose two guarantor phone numbers are equal or match the applicant's own.
     */
    protected long extraMember(Tenant tenant, int ledgerNumber, String nameSuffix, String phone) {
        String ledgerID = LedgerIDGen.generate(tenant.ledgerPrefix(), ledgerNumber);

        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO users
                    (organization_id, ledgerid, ledger_number, first_name, last_name, email, phone,
                     password, status, role, payment_type, savings_balance, loan_balance,
                     shares_balance, saving_plan, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 'ROLE_MEMBER', 'SELF_PAY',
                        0.00, 0.00, 0.00, 0.00, now())
                RETURNING id
                """, Long.class,
                tenant.organizationId(), ledgerID, ledgerNumber,
                "Chidi", nameSuffix,
                nameSuffix + "@" + tenant.slug() + ".test", phone,
                passwordEncoder.encode(TenantFixture.PASSWORD));

        assertThat(id).as("the extra cooperator must have been inserted").isNotNull();
        return id;
    }

    protected ResultActions approve(String adminToken, long loanId) throws Exception {
        return as(adminToken, put("/api/admin/loan/{id}/approve", loanId));
    }

    protected ResultActions reject(String adminToken, long loanId, String remark) throws Exception {
        return as(adminToken, put("/api/admin/loan/{id}/reject", loanId),
                LoanDto.builder().remark(remark).build());
    }

    protected ResultActions repay(String memberToken, long loanId, String amount) throws Exception {
        return as(memberToken, post("/api/loan/{id}/repay", loanId),
                RepayDto.builder().amount(new BigDecimal(amount)).build());
    }

    // ------------------------------------------------------------------ committed state

    protected BigDecimal loanBalance(long loanId) {
        return jdbcTemplate.queryForObject("SELECT balance FROM loan WHERE id = ?",
                BigDecimal.class, loanId);
    }

    protected BigDecimal loanAmount(long loanId) {
        return jdbcTemplate.queryForObject("SELECT amount FROM loan WHERE id = ?",
                BigDecimal.class, loanId);
    }

    protected BigDecimal scheduledInstalment(long loanId) {
        return jdbcTemplate.queryForObject("SELECT repay_amount FROM loan WHERE id = ?",
                BigDecimal.class, loanId);
    }

    protected String loanStatus(long loanId) {
        return jdbcTemplate.queryForObject("SELECT status FROM loan WHERE id = ?",
                String.class, loanId);
    }

    protected String loanRemark(long loanId) {
        return jdbcTemplate.queryForObject("SELECT remark FROM loan WHERE id = ?",
                String.class, loanId);
    }

    protected BigDecimal memberLoanBalance(Tenant tenant) {
        return jdbcTemplate.queryForObject("SELECT loan_balance FROM users WHERE id = ?",
                BigDecimal.class, tenant.member().id());
    }

    /** Sum of every repayment recorded against one loan, or zero when there are none. */
    protected BigDecimal totalRepaid(long loanId) {
        BigDecimal total = jdbcTemplate.queryForObject(
                "SELECT coalesce(sum(amount), 0) FROM repay WHERE loan_id = ?",
                BigDecimal.class, loanId);
        return total == null ? BigDecimal.ZERO : total;
    }

    protected long repaymentCount(long loanId) {
        return countWhere("repay WHERE loan_id = ?", loanId);
    }
}
