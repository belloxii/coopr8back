package com.invo.coopr8.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.invo.coopr8.model.Loan;
import com.invo.coopr8.repository.LoanRepository;
import com.invo.coopr8.security.AuthPrincipal;
import com.invo.coopr8.security.CurrentAuth;

import lombok.RequiredArgsConstructor;

/**
 * Standing as guarantor on another member's loan.
 *
 * <p>Guarantors carry real liability, so the two things that matter here are that a member sees
 * only the requests actually addressed to them, and that only the nominated guarantor can answer
 * one. Both are now enforced against the loan's own {@code guarantor1}/{@code guarantor2}
 * columns, within the caller's cooperative.
 */
@RestController
@RequestMapping("/api/guarantor")
@RequiredArgsConstructor
public class GuarantorController {

    private final LoanRepository loanRepository;

    /**
     * Loans on which the caller is named as a guarantor.
     *
     * <p>Answered by a scoped query. It used to read <em>every loan on the platform</em> into
     * memory and filter in Java -- correct only by accident, and the accident stopped holding the
     * moment a second cooperative existed.
     */
    @GetMapping("/myrequests")
    public List<Loan> getGuarantorRequests() {
        AuthPrincipal principal = CurrentAuth.require();
        return loanRepository.findGuarantorRequests(principal.organizationId(), principal.userId());
    }

    @PutMapping("/accept")
    public ResponseEntity<?> acceptGuarantor(@RequestParam Long loanId) {
        return respond(loanId, "ACCEPTED", "Guarantor request accepted.");
    }

    @PutMapping("/decline")
    public ResponseEntity<?> declineGuarantor(@RequestParam Long loanId) {
        return respond(loanId, "DECLINED", "Guarantor request declined.");
    }

    /**
     * Records the caller's answer to a guarantor request.
     *
     * <p>The loan is loaded within the caller's cooperative, so a loan id from another one is
     * reported as absent. The caller must then match one of the two guarantor slots -- being a
     * member of the right cooperative is not enough to answer somebody else's request.
     */
    private ResponseEntity<?> respond(Long loanId, String decision, String message) {
        AuthPrincipal principal = CurrentAuth.require();

        Loan loan = loanRepository.findByIdAndOrganizationId(loanId, principal.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Loan not found."));

        if (isGuarantor(loan.getGuarantor1(), principal)) {
            loan.setGuarantor1Status(decision);
        } else if (isGuarantor(loan.getGuarantor2(), principal)) {
            loan.setGuarantor2Status(decision);
        } else {
            return ResponseEntity.badRequest().body("User is not a guarantor for this loan");
        }

        loanRepository.save(loan);
        return ResponseEntity.ok(message);
    }

    private static boolean isGuarantor(com.invo.coopr8.model.User guarantor, AuthPrincipal principal) {
        return guarantor != null && principal.userId().equals(guarantor.getId());
    }
}
