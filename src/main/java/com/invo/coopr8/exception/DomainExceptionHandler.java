package com.invo.coopr8.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.invo.coopr8.dto.CoopResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Turns the six checked domain exceptions into client refusals.
 *
 * <p><strong>Why this exists.</strong> The application had no {@code @ControllerAdvice} at all, and
 * every controller method that declares {@code throws LoanException} (or {@code RepayException},
 * {@code SharesException}, ...) had nothing downstream to catch it. Any one of them escaped the
 * dispatcher servlet and became HTTP 500 with a stack trace in the log -- so "your repayment must
 * be in multiples of ₦33,333.33", a message written for a member to read, arrived as a server
 * fault. A cooperative watching its error rate could not tell a rejected form from a broken
 * deployment.
 *
 * <p><strong>What it deliberately does not do.</strong> It handles only these six types. It does
 * not extend {@code ResponseEntityExceptionHandler} and does not name
 * {@code ResponseStatusException}, {@code HttpMessageNotReadableException},
 * {@code AccessDeniedException} or {@code AuthenticationException}: those already carry their own
 * status and are Spring's or the security filter chain's to answer. In particular the 404 that
 * hides another cooperative's rows, and the 401/403 the filter chain produces, must keep flowing
 * past this class untouched.
 *
 * <p>All six are business refusals of a well-formed request, so they map to 400 and reuse the
 * established refusal envelope: {@code responseCode} {@code "419"} with the message the service
 * wrote. Those messages are member-facing by construction -- none of them carries internal state,
 * an identifier the caller did not already supply, or anything about another tenant.
 */
@RestControllerAdvice
@Slf4j
public class DomainExceptionHandler {

    @ExceptionHandler({
            LoanException.class,
            RepayException.class,
            SharesException.class,
            SavingException.class,
            UserException.class,
            NotificationException.class })
    public ResponseEntity<CoopResponse> onDomainRefusal(Exception refusal) {
        // Logged without the stack trace: a refused application is an ordinary outcome, and
        // stack traces here are what made these look like faults in the first place.
        log.warn("Request refused: {}: {}", refusal.getClass().getSimpleName(), refusal.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(CoopResponse.builder()
                        .responseCode("419")
                        .responseMessage(refusal.getMessage())
                        .build());
    }
}
