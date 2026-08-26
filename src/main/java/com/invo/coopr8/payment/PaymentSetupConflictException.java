package com.invo.coopr8.payment;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Payment setup cannot proceed, and a person has to look.
 *
 * <p>Raised for the states where continuing automatically would risk a cooperative's money going
 * somewhere nobody chose: another setup attempt is already running, the provider already holds more
 * than one account for this destination, the provider could not be searched, or the cooperative is
 * already connected to a different account.
 *
 * <p><strong>A conflict, not a failure.</strong> {@code 409} rather than {@code 500} because nothing is
 * broken -- the request is answerable, just not by guessing. And unchecked rather than checked so that
 * it rolls a surrounding transaction back by default; a setup step that ends here must leave no
 * half-written configuration behind.
 */
public class PaymentSetupConflictException extends ResponseStatusException {

    public PaymentSetupConflictException(String reason, Throwable cause) {
        super(HttpStatus.CONFLICT, reason, cause);
    }
}
