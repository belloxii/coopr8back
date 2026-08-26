package com.invo.coopr8.payment;

/**
 * A payment provider could not be reached, or answered in a way COOPR8 will not act on.
 *
 * <p>Unchecked deliberately. A provider failure part-way through a payment must roll back whatever
 * the surrounding transaction had staged, and Spring rolls back on unchecked exceptions by default --
 * a checked exception here would commit a half-finished setup unless every caller remembered to say
 * otherwise. The platform already learned this the hard way with {@code LoanException}.
 */
public class PaymentProviderException extends RuntimeException {

    public PaymentProviderException(String message) {
        super(message);
    }

    public PaymentProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
