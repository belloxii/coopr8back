package com.invo.coopr8.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.invo.coopr8.dto.LoanDto;
import com.invo.coopr8.dto.LoanResponse;
import com.invo.coopr8.exception.LoanException;
import com.invo.coopr8.model.Loan;
import com.invo.coopr8.model.Repay;
import com.invo.coopr8.model.User;
import com.invo.coopr8.repository.LoanRepository;
import com.invo.coopr8.repository.RepayRepository;
import com.invo.coopr8.security.AuthPrincipal;
import com.invo.coopr8.security.CurrentAuth;
import com.invo.coopr8.service.LoanService;
import com.invo.coopr8.service.UserService;

import lombok.AllArgsConstructor;

/** A member's own loans: applying, listing, and repaying. */
@RestController
@RequestMapping("/api/loan")
@AllArgsConstructor
public class LoanController {

    private final UserService userService;
    private final LoanService loanService;
    private final LoanRepository loanRepository;
    private final RepayRepository repayRepository;

    @PostMapping("/apply")
    public LoanResponse applyLoan(@RequestBody LoanDto loanRequest) throws LoanException {
        User user = userService.requireCurrentUser();
        return loanService.applyLoan(user, loanRequest);
    }

    @GetMapping("/myloans")
    public List<Loan> myLoans() throws LoanException {
        AuthPrincipal principal = CurrentAuth.require();
        return loanRepository.findByUser_IdAndOrganizationId(
                principal.userId(), principal.organizationId());
    }

    /**
     * One loan by id.
     *
     * <p>Scoped to the caller's cooperative, and then to people with a stake in this particular
     * loan: the borrower, either nominated guarantor, or an administrator. Previously any
     * authenticated member could read any loan on the platform by its id -- amount, purpose,
     * balance and borrower included.
     */
    @GetMapping("/{loanId}")
    public Loan loanById(@PathVariable Long loanId) throws LoanException {
        AuthPrincipal principal = CurrentAuth.require();

        Loan loan = loanRepository.findByIdAndOrganizationId(loanId, principal.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Loan not found."));

        if (!principal.isAdmin() && !isParty(loan, principal)) {
            // 404, not 403: a member has no business learning that a loan they are unconnected
            // to exists at all.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Loan not found.");
        }
        return loan;
    }

    @GetMapping("/myrepays")
    public List<Repay> myRepay() {
        AuthPrincipal principal = CurrentAuth.require();
        return repayRepository.findByUser_IdAndOrganizationId(
                principal.userId(), principal.organizationId());
    }

    /** Borrower or nominated guarantor. */
    private static boolean isParty(Loan loan, AuthPrincipal principal) {
        Long callerId = principal.userId();
        return matches(loan.getUser(), callerId)
                || matches(loan.getGuarantor1(), callerId)
                || matches(loan.getGuarantor2(), callerId);
    }

    private static boolean matches(User user, Long callerId) {
        return user != null && callerId.equals(user.getId());
    }
}
