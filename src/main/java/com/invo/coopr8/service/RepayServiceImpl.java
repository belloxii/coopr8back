package com.invo.coopr8.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.invo.coopr8.dto.RepayDto;
import com.invo.coopr8.dto.RepayResponse;
import com.invo.coopr8.exception.LoanException;
import com.invo.coopr8.exception.RepayException;
import com.invo.coopr8.model.Loan;
import com.invo.coopr8.model.Organization;
import com.invo.coopr8.model.Repay;
import com.invo.coopr8.model.User;
import com.invo.coopr8.repository.LoanRepository;
import com.invo.coopr8.repository.RepayRepository;
import com.invo.coopr8.repository.UserRepository;
import com.invo.coopr8.utils.TxnIdGen;

import lombok.AllArgsConstructor;

/**
 * Online loan repayment.
 *
 * <p>The loan is loaded within the paying member's own cooperative and must belong to that
 * member. Both checks are new: the loan was previously fetched by id alone, so a repayment could
 * be credited against a loan belonging to another member -- or another cooperative -- while the
 * payer's own outstanding balance was the one reduced.
 *
 * <p>Both of those refusals answer 404 through {@code ResponseStatusException}, the convention the
 * rest of the API already uses for a resource the caller cannot see ({@code LoanController#loanById},
 * {@code SharesServiceImpl#requireApprovableShare}). They previously threw {@code LoanException},
 * which -- with no handler for it anywhere -- escaped the dispatcher as HTTP 500: a cross-tenant
 * repayment attempt was refused, correctly, but announced itself as a server fault.
 *
 * <p><strong>The final instalment absorbs the residual.</strong> Approval divides the total into
 * instalments rounded to the kobo, so the instalments need not add back up to the total. Requiring
 * every payment to be an exact multiple of the instalment therefore stranded the difference: a
 * ₦100,000 loan over 3 months settled at ₦0.01 outstanding, and ₦0.01 was not a multiple of
 * ₦33,333.33, so the loan could never be closed and the member's balance could never return to
 * zero. Paying the whole outstanding balance is now always allowed, which is the same thing as the
 * last payment being {@code total - sum(previous payments)} -- and the overpayment ceiling is
 * checked first, so absorbing the residual never licenses paying more than is owed.
 */
@Service
@AllArgsConstructor
public class RepayServiceImpl implements RepayService {

    private final LoanRepository loanRepository;
    private final RepayRepository repayRepository;
    private final UserRepository userRepository;
    private final OrganizationService organizationService;

@Override
@Transactional
public RepayResponse repayNow(User user, Long loanId, RepayDto repayDto) throws LoanException, RepayException {

    if (user == null) {
        throw new RepayException("User not found");
    }

    Organization organization = organizationService.requireForUser(user);

    Loan loan = loanRepository.findByIdAndOrganizationId(loanId, organization.getId())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loan not found."));

    // A member repays their own loan. Anything else is reported the same way as a loan that
    // does not exist, which is what a mistyped or borrowed loan id deserves.
    if (loan.getUser() == null || !loan.getUser().getId().equals(user.getId())) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Loan not found.");
    }

    BigDecimal repaymentAmount = scaled(repayDto.getAmount());
    BigDecimal repayAmount = scaled(loan.getRepayAmount());
    BigDecimal currentBalance = scaled(loan.getBalance());

    if (repaymentAmount.compareTo(BigDecimal.ZERO) <= 0 || repayAmount.compareTo(BigDecimal.ZERO) <= 0) {
        // A loan awaiting approval has no repayment plan yet (repay_amount is NULL until
        // approval schedules it), which lands here rather than as a null dereference.
        throw new RepayException("Invalid repayment amount or repayment plan.");
    }

    // Checked before the instalment rule, so that settling the loan can never become a licence
    // to overpay: the outstanding balance is the ceiling regardless of which rule admits the
    // payment below.
    if (repaymentAmount.compareTo(currentBalance) > 0) {
        throw new RepayException("Repayment amount exceeds outstanding loan balance.");
    }

    // Instalments are the rounded per-month figure, and rounding is why the two rules below are
    // both needed. Approval schedules `amount / duration` rounded to the kobo, so
    // instalment x duration need not equal the total: 100,000.00 over 3 months schedules
    // 33,333.33, and three of those come to 99,999.99. Insisting on exact multiples alone left
    // the last 0.01 permanently unpayable -- the loan could not close and the member's balance
    // could not reach zero.
    //
    // The final payment therefore absorbs the residual, and it is the whole outstanding balance
    // by definition: `balance` is maintained as total - sum(payments so far), so paying it is
    // exactly `total_repayable - sum(all_previous_instalments)`. It is never computed as
    // `rounded_instalment + residual`, which could exceed the agreed total, and the stored total
    // is never adjusted to make the division come out evenly.
    boolean settlesInFull = repaymentAmount.compareTo(currentBalance) == 0;
    boolean wholeInstalments =
            repaymentAmount.remainder(repayAmount).compareTo(BigDecimal.ZERO) == 0;

    if (!settlesInFull && !wholeInstalments) {
        throw new RepayException("Repayment must be in multiples of ₦"
                + repayAmount.toPlainString() + ", or ₦" + currentBalance.toPlainString()
                + " to settle this loan in full.");
    }

    BigDecimal remainingBalance = currentBalance.subtract(repaymentAmount).setScale(2, RoundingMode.HALF_UP);

    Repay repayLoan = Repay.builder()
        .amount(repaymentAmount)
        .txnId(TxnIdGen.generateTransactionId())
        .balance(remainingBalance)
        .loan(loan)
        .parentLoanId(loan.getId())
        .loanType(loan.getType())
        .status("approved")
        .channel("PAYSTACK")
        .user(user)
        .organization(organization)
        .build();

    repayRepository.save(repayLoan);
    loan.setBalance(remainingBalance);

    if (remainingBalance.compareTo(BigDecimal.ZERO) == 0) {
        loan.setStatus("completed");
    }

    loanRepository.save(loan);

    BigDecimal updatedLoanBalance = scaled(user.getLoanBalance()).subtract(repaymentAmount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    user.setLoanBalance(updatedLoanBalance);
    userRepository.save(user);

    return RepayResponse.builder()
        .responseCode("100")
        .responseMessage("Repayment successful")
        .repay(repayLoan)
        .build();
}

    /**
     * A money amount at the platform's two-kobo scale, treating absent as zero.
     *
     * <p>{@code users.loan_balance} and {@code loan.repay_amount} are both nullable with no
     * default -- a member who has never borrowed, and a loan not yet approved -- so reading them
     * arithmetically has to say what absent means rather than fail on it.
     */
    private static BigDecimal scaled(BigDecimal amount) {
        return amount == null
                ? BigDecimal.ZERO.setScale(2)
                : amount.setScale(2, RoundingMode.HALF_UP);
    }

}
