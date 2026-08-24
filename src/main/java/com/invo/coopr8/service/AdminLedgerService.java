package com.invo.coopr8.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.invo.coopr8.dto.LedgerRowResult;
import com.invo.coopr8.dto.LedgerUploadRequest;
import com.invo.coopr8.dto.LedgerUploadRow;
import com.invo.coopr8.dto.LedgerUploadResult;
import com.invo.coopr8.dto.ManualPostingRequest;
import com.invo.coopr8.model.Loan;
import com.invo.coopr8.model.Repay;
import com.invo.coopr8.model.Saving;
import com.invo.coopr8.model.User;
import com.invo.coopr8.repository.LoanRepository;
import com.invo.coopr8.repository.RepayRepository;
import com.invo.coopr8.repository.SavingRepository;
import com.invo.coopr8.repository.UserRepository;
import com.invo.coopr8.security.CurrentAuth;
import com.invo.coopr8.utils.TxnIdGen;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Posts savings and loan repayments on behalf of members.
 *
 * <ul>
 *   <li><b>Ministry batch</b> ({@link #batchUpload}) — the salary-deduction
 *       schedule for payroll (PSN) members. Rows are matched by PSN and
 *       recorded with channel {@code SALARY}.</li>
 *   <li><b>Manual posting</b> ({@link #manualSaving}/{@link #manualRepay}) —
 *       a self-pay member sent a bank-transfer receipt; the admin records it
 *       with channel {@code TRANSFER}.</li>
 * </ul>
 *
 * <p><strong>Tenant scope.</strong> Every entry point takes the organization from the calling
 * administrator's token and threads it through each lookup. This is the one service in the
 * application that <em>writes money</em> against a member record it found by search rather than
 * by ownership, so an unscoped lookup here is worse than a leak: before Phase 2, a PSN in an
 * uploaded payroll file was matched with {@code findFirstByPsn} across the whole platform, so
 * one cooperative's payroll batch could credit savings and clear loans on another cooperative's
 * member. The manual-posting pair had the same hole via a bare {@code findById}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminLedgerService {

    private static final int MAX_ROWS = 500;
    private static final List<String> ACTIVE_LOAN_STATUSES = List.of("approved", "APPROVED", "active", "ACTIVE");

    private final UserRepository userRepository;
    private final LoanRepository loanRepository;
    private final SavingRepository savingRepository;
    private final RepayRepository repayRepository;
    private final OrganizationService organizationService;

    // ------------------------------------------------------------------ batch
    @Transactional
    public LedgerUploadResult batchUpload(LedgerUploadRequest request) {
        Long organizationId = CurrentAuth.requireOrganizationId();

        List<LedgerUploadRow> rows = request == null ? null : request.getRows();
        if (rows == null || rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The upload must contain at least one row.");
        }
        if (rows.size() > MAX_ROWS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A batch may contain at most " + MAX_ROWS + " rows.");
        }

        List<LedgerRowResult> results = new ArrayList<>();
        int matchedCount = 0, savingCount = 0, repayCount = 0;
        BigDecimal totalSaved = BigDecimal.ZERO, totalRepaid = BigDecimal.ZERO;

        for (int index = 0; index < rows.size(); index++) {
            LedgerUploadRow row = rows.get(index);
            int rowNumber = row != null && row.getRowNumber() > 0 ? row.getRowNumber() : index + 2;
            String psn = row == null ? null : trim(row.getPsn());

            if (psn == null || psn.isEmpty()) {
                results.add(rejected(rowNumber, psn, "PSN is required."));
                continue;
            }

            // Scoped: a PSN belonging to another cooperative's member reports as unmatched,
            // which is both the safe answer and the honest one for this administrator.
            User user = userRepository.findFirstByPsnAndOrganizationId(psn, organizationId).orElse(null);
            if (user == null) {
                results.add(rejected(rowNumber, psn, "No member found with this PSN."));
                continue;
            }

            BigDecimal saving = positiveOrNull(row.getSavingAmount());
            BigDecimal repay = positiveOrNull(row.getRepayAmount());
            if (saving == null && repay == null) {
                results.add(LedgerRowResult.builder()
                        .rowNumber(rowNumber).psn(psn).matched(true)
                        .memberName(fullName(user)).ledgerId(user.getLedgerID())
                        .savingPosted(BigDecimal.ZERO).repayPosted(BigDecimal.ZERO)
                        .message("Skipped — no saving or repayment amount.")
                        .build());
                matchedCount++;
                continue;
            }

            BigDecimal savedNow = BigDecimal.ZERO;
            BigDecimal repaidNow = BigDecimal.ZERO;
            List<String> notes = new ArrayList<>();

            if (saving != null) {
                postSaving(user, saving, "SALARY");
                savedNow = saving;
                savingCount++;
                notes.add("Saving ₦" + saving.toPlainString());
            }
            if (repay != null) {
                repaidNow = applyRepayment(user, repay, "SALARY", organizationId);
                if (repaidNow.compareTo(BigDecimal.ZERO) > 0) {
                    repayCount++;
                    notes.add("Repaid ₦" + repaidNow.toPlainString());
                    if (repaidNow.compareTo(repay) < 0) {
                        notes.add("(₦" + repay.subtract(repaidNow).toPlainString() + " unapplied — no outstanding balance)");
                    }
                } else {
                    notes.add("Repayment skipped — member has no outstanding loan");
                }
            }

            matchedCount++;
            totalSaved = totalSaved.add(savedNow);
            totalRepaid = totalRepaid.add(repaidNow);
            results.add(LedgerRowResult.builder()
                    .rowNumber(rowNumber).psn(psn).matched(true)
                    .memberName(fullName(user)).ledgerId(user.getLedgerID())
                    .savingPosted(savedNow).repayPosted(repaidNow)
                    .message(String.join("; ", notes))
                    .build());
        }

        return LedgerUploadResult.builder()
                .totalRows(rows.size())
                .matchedCount(matchedCount)
                .rejectedCount(rows.size() - matchedCount)
                .savingCount(savingCount)
                .repayCount(repayCount)
                .totalSaved(totalSaved)
                .totalRepaid(totalRepaid)
                .results(results)
                .build();
    }

    // ----------------------------------------------------------------- manual
    @Transactional
    public Saving manualSaving(ManualPostingRequest request) {
        Long organizationId = CurrentAuth.requireOrganizationId();
        User user = loadUser(request, organizationId);
        BigDecimal amount = positiveOrNull(request.getAmount());
        if (amount == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A saving amount greater than zero is required.");
        }
        return postSaving(user, amount, "TRANSFER");
    }

    @Transactional
    public BigDecimal manualRepay(ManualPostingRequest request) {
        Long organizationId = CurrentAuth.requireOrganizationId();
        User user = loadUser(request, organizationId);
        BigDecimal amount = positiveOrNull(request.getAmount());
        if (amount == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A repayment amount greater than zero is required.");
        }

        BigDecimal applied;
        if (request.getLoanId() != null) {
            // 404, not 400: the lookup is scoped to this cooperative, so a miss means either the
            // loan does not exist or it belongs to another cooperative -- and those two cases must
            // be indistinguishable. A 400 would also mis-describe the request, which is well
            // formed; it simply names a loan this administrator cannot see.
            Loan loan = loanRepository.findByIdAndOrganizationId(request.getLoanId(), organizationId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loan not found."));

            // The loan and the member both belong to this cooperative, but they still have to
            // belong to each other -- otherwise a mistyped loan id would credit one member's
            // transfer against another member's debt. This one stays 400: both rows are visible to
            // this administrator, so the request is genuinely bad rather than pointed at something
            // that is not theirs.
            if (loan.getUser() == null || !loan.getUser().getId().equals(user.getId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "That loan does not belong to this member.");
            }
            applied = applyToLoan(user, loan, amount, "TRANSFER");
        } else {
            applied = applyRepayment(user, amount, "TRANSFER", organizationId);
        }

        if (applied.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The member has no outstanding loan balance to repay.");
        }
        return applied;
    }

    // ------------------------------------------------------------ shared core
    /** Credit a member's savings balance and record the saving. */
    private Saving postSaving(User user, BigDecimal amount, String channel) {
        BigDecimal current = user.getSavingsBalance() != null ? user.getSavingsBalance() : BigDecimal.ZERO;
        BigDecimal updated = current.add(amount);
        user.setSavingsBalance(updated);
        userRepository.save(user);

        Saving saving = Saving.builder()
                .amount(amount)
                .txnId(TxnIdGen.generateTransactionId())
                .balance(updated)
                .status("approved")
                .channel(channel)
                .user(user)
                .organization(organizationService.requireForUser(user))
                .build();
        log.info("Saving posted: user={}, amount={}, channel={}", user.getId(), amount, channel);
        return savingRepository.save(saving);
    }

    /** Distribute a repayment across the member's approved loans (oldest first). */
    private BigDecimal applyRepayment(User user, BigDecimal amount, String channel, Long organizationId) {
        List<Loan> loans = new ArrayList<>(loanRepository
                .findByUserAndStatusInAndOrganizationId(user, ACTIVE_LOAN_STATUSES, organizationId));
        loans.sort(Comparator.comparing(Loan::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));

        BigDecimal remaining = amount;
        BigDecimal appliedTotal = BigDecimal.ZERO;
        for (Loan loan : loans) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal balance = loan.getBalance() == null ? BigDecimal.ZERO : loan.getBalance();
            if (balance.compareTo(BigDecimal.ZERO) <= 0) continue;
            BigDecimal applied = remaining.min(balance);
            applyToLoanInternal(user, loan, applied, channel);
            appliedTotal = appliedTotal.add(applied);
            remaining = remaining.subtract(applied);
        }
        return appliedTotal.setScale(2, RoundingMode.HALF_UP);
    }

    /** Apply a repayment to one specific loan, capped at its outstanding balance. */
    private BigDecimal applyToLoan(User user, Loan loan, BigDecimal amount, String channel) {
        BigDecimal balance = loan.getBalance() == null ? BigDecimal.ZERO : loan.getBalance();
        if (balance.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        BigDecimal applied = amount.min(balance);
        applyToLoanInternal(user, loan, applied, channel);
        return applied.setScale(2, RoundingMode.HALF_UP);
    }

    private void applyToLoanInternal(User user, Loan loan, BigDecimal applied, String channel) {
        BigDecimal balance = loan.getBalance() == null ? BigDecimal.ZERO : loan.getBalance();
        BigDecimal newBalance = balance.subtract(applied).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        Repay repay = Repay.builder()
                .amount(applied.setScale(2, RoundingMode.HALF_UP))
                .txnId(TxnIdGen.generateTransactionId())
                .balance(newBalance)
                .loan(loan)
                .parentLoanId(loan.getId())
                .loanType(loan.getType())
                .status("approved")
                .channel(channel)
                .user(user)
                .organization(organizationService.requireForUser(user))
                .build();
        repayRepository.save(repay);

        loan.setBalance(newBalance);
        if (newBalance.compareTo(BigDecimal.ZERO) == 0) {
            loan.setStatus("completed");
        }
        loanRepository.save(loan);

        BigDecimal loanBalance = user.getLoanBalance() == null ? BigDecimal.ZERO : user.getLoanBalance();
        user.setLoanBalance(loanBalance.subtract(applied).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
        userRepository.save(user);
        log.info("Repay posted: user={}, loan={}, amount={}, channel={}", user.getId(), loan.getId(), applied, channel);
    }

    // ----------------------------------------------------------------- helpers
    /**
     * Resolve the member a manual posting names, within the caller's own cooperative.
     *
     * <p>The two failures are deliberately different statuses. A missing {@code userId} is a
     * malformed request: 400. A {@code userId} that this cooperative's scoped lookup does not
     * return is either nonexistent or another cooperative's member, and both must answer 404 --
     * the same answer, so that a posting aimed across the tenant boundary cannot be told apart
     * from one aimed at nothing at all.
     */
    private User loadUser(ManualPostingRequest request, Long organizationId) {
        if (request == null || request.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A member id is required.");
        }
        return userRepository.findByIdAndOrganizationId(request.getUserId(), organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found."));
    }

    private LedgerRowResult rejected(int rowNumber, String psn, String message) {
        return LedgerRowResult.builder()
                .rowNumber(rowNumber).psn(psn).matched(false)
                .savingPosted(BigDecimal.ZERO).repayPosted(BigDecimal.ZERO)
                .message(message)
                .build();
    }

    private String fullName(User user) {
        return ((user.getFirstName() == null ? "" : user.getFirstName()) + " "
                + (user.getLastName() == null ? "" : user.getLastName())).trim();
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private BigDecimal positiveOrNull(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0 ? value : null;
    }
}
