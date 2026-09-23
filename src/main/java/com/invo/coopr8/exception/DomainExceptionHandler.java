package com.invo.coopr8.exception;

import java.util.Comparator;
import java.util.stream.Collectors;

import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import com.invo.coopr8.dto.CoopResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Turns refused requests into answers a client can read.
 *
 * <p><strong>Why this exists.</strong> The application had no {@code @ControllerAdvice} at all, and
 * every controller method that declares {@code throws LoanException} (or {@code RepayException},
 * {@code SharesException}, ...) had nothing downstream to catch it. Any one of them escaped the
 * dispatcher servlet and became HTTP 500 with a stack trace in the log -- so "your repayment must
 * be in multiples of ₦33,333.33", a message written for a member to read, arrived as a server
 * fault. A cooperative watching its error rate could not tell a rejected form from a broken
 * deployment.
 *
 * <p>The same was true of every {@link ResponseStatusException} in the codebase. Spring answers one
 * with the right status but an empty body unless {@code server.error.include-message} is set, which
 * it is not: {@code CurrentAuth.requireAdmin}'s "This action requires a cooperative administrator."
 * and the configuration endpoints' "Changing a rate or a price requires a reason" were written,
 * thrown, and never delivered. The client got a bare status and had to guess.
 *
 * <p><strong>What it deliberately does not do.</strong> It does not name
 * {@code AccessDeniedException} or {@code AuthenticationException}. Those are the security filter
 * chain's to answer and are handled before a controller is entered, so the 401 and 403 it produces
 * keep flowing past this class untouched. Nor does it invent a status: a
 * {@link ResponseStatusException} is answered with the status it was thrown with, which is what
 * keeps the deliberate 404 that hides another cooperative's rows a 404 rather than becoming a 403 or
 * a 400 on its way out.
 *
 * <p><strong>The envelope.</strong> Every answer here reuses {@link CoopResponse}:
 * {@code responseCode} {@code "419"} for an ordinary refusal, {@code "409"} for a conflict, and
 * {@code "500"} for the two cases below that are genuinely the server's fault. The message is the one
 * the thrower wrote -- those are all written for a person to read and none of them carries internal
 * state, an identifier the caller did not already supply, or anything about another tenant. The one
 * exception is {@link DataIntegrityViolationException}, whose message is a constraint name and a SQL
 * fragment; that one is replaced.
 */
@RestControllerAdvice
@Slf4j
public class DomainExceptionHandler {

    /** The refusal code the platform already used before this class existed. */
    private static final String REFUSED = "419";

    /** "There is already one of these." */
    private static final String CONFLICT = "409";

    private static final String FAULT = "500";

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
                        .responseCode(REFUSED)
                        .responseMessage(refusal.getMessage())
                        .build());
    }

    /**
     * Delivers the reason a {@link ResponseStatusException} was thrown with, at its own status.
     *
     * <p>The status is never rewritten. A 404 thrown to hide another cooperative's row stays a 404
     * with the same {@code "Not found."} it would have given for a row that never existed, because
     * distinguishing the two is the leak the 404 exists to prevent.
     *
     * <p>A null reason falls back to a generic sentence rather than a null body field: Spring throws
     * these itself for an unsupported method or media type, and those carry no reason of their own.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<CoopResponse> onStatusRefusal(ResponseStatusException refusal) {
        HttpStatusCode status = refusal.getStatusCode();
        String message = refusal.getReason() == null
                ? defaultMessageFor(status)
                : refusal.getReason();

        if (status.is5xxServerError()) {
            log.error("Request failed: {} {}", status, message, refusal);
        } else {
            log.warn("Request refused: {} {}", status, message);
        }

        return ResponseEntity.status(status)
                .body(CoopResponse.builder()
                        .responseCode(codeFor(status))
                        .responseMessage(message)
                        .build());
    }

    /**
     * Reports what a {@code @Valid} request body got wrong, field by field.
     *
     * <p>Without this, a submission that fails bean validation is a 400 with an empty body and the
     * administrator is told only that something is wrong with a form of twenty fields. The messages
     * joined here are the ones declared on the DTOs, each of which names its field in words -- "State
     * the annual interest rate. Use 0 for interest-free loans." rather than
     * {@code interestRate: must not be null}.
     *
     * <p>Sorted by field name so the same set of errors always reads the same way, rather than in
     * whatever order the validator happened to visit them.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CoopResponse> onInvalidBody(MethodArgumentNotValidException invalid) {
        String message = invalid.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .filter(text -> text != null && !text.isBlank())
                .distinct()
                .collect(Collectors.joining(" "));

        if (message.isBlank()) {
            message = "Some of the values submitted are not valid.";
        }

        log.warn("Request refused: {} field error(s): {}",
                invalid.getBindingResult().getErrorCount(), message);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(CoopResponse.builder()
                        .responseCode(REFUSED)
                        .responseMessage(message)
                        .build());
    }

    /**
     * A unique index or foreign key the application checked for and lost a race on.
     *
     * <p>Two administrators creating a loan product with the same name in the same instant both pass
     * {@code requireLoanProductNameIsFree}, and the second insert is refused by
     * {@code ux_organization_loan_type_org_name}. That is the index doing its job, and the loser
     * should be told the same thing the pre-check would have told them -- not handed a 500.
     *
     * <p>The exception's own message is discarded. It contains the constraint name, the table, and
     * often the conflicting values; none of that belongs in a response, and a constraint name is not
     * something the person at the screen can act on. Logged in full at error level, because a
     * violation the application did not pre-check is a bug worth seeing.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<CoopResponse> onIntegrityViolation(DataIntegrityViolationException clash) {
        log.error("Database refused a write: {}", clash.getMostSpecificCause().getMessage(), clash);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(CoopResponse.builder()
                        .responseCode(CONFLICT)
                        .responseMessage("That change conflicts with something already saved. "
                                + "Reload the page and try again.")
                        .build());
    }

    /**
     * A concurrent financial or profile change won the version race. Reporting a conflict gives
     * the caller a safe retry path; treating it as a generic 500 encourages blind resubmission.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<CoopResponse> onOptimisticLock(OptimisticLockingFailureException clash) {
        log.warn("Concurrent update refused: {}", clash.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(CoopResponse.builder()
                        .responseCode(CONFLICT)
                        .responseMessage("This record changed while you were working. Reload and try again.")
                        .build());
    }

    private static String codeFor(HttpStatusCode status) {
        if (status.value() == HttpStatus.CONFLICT.value()) {
            return CONFLICT;
        }
        return status.is5xxServerError() ? FAULT : REFUSED;
    }

    private static String defaultMessageFor(HttpStatusCode status) {
        if (status.value() == HttpStatus.NOT_FOUND.value()) {
            return "Not found.";
        }
        return status.is5xxServerError()
                ? "Something went wrong on our side. Please try again."
                : "That request could not be accepted.";
    }
}
