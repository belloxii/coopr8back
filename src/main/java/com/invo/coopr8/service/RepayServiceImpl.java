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

    BigDecimal repaymentAmount = repayDto.getAmount().setScale(2, RoundingMode.HALF_UP);
    BigDecimal repayAmount = loan.getRepayAmount().setScale(2, RoundingMode.HALF_UP);
    BigDecimal currentBalance = loan.getBalance().setScale(2, RoundingMode.HALF_UP);

    if (repaymentAmount.compareTo(BigDecimal.ZERO) <= 0 || repayAmount.compareTo(BigDecimal.ZERO) <= 0) {
        throw new RepayException("Invalid repayment amount or repayment plan.");
    }

    if (repaymentAmount.remainder(repayAmount).compareTo(BigDecimal.ZERO) != 0) {
        throw new RepayException("Repayment must be in multiples of ₦" + repayAmount.toPlainString());
    }

    if (repaymentAmount.compareTo(currentBalance) > 0) {
        throw new RepayException("Repayment amount exceeds outstanding loan balance.");
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

    BigDecimal updatedLoanBalance = user.getLoanBalance().subtract(repaymentAmount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    user.setLoanBalance(updatedLoanBalance);
    userRepository.save(user);

    return RepayResponse.builder()
        .responseCode("100")
        .responseMessage("Repayment successful")
        .repay(repayLoan)
        .build();
}

}
