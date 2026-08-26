package com.invo.coopr8.payment;

/**
 * A payment reference is already recorded.
 *
 * <p>Raised from the database's uniqueness constraint on {@code (provider, provider_reference)}
 * rather than from a prior existence check, so it holds against a concurrent insert of the same
 * reference and not merely against a sequential one.
 *
 * <p>Unchecked, so the reservation transaction rolls back without every caller having to remember to
 * ask for it.
 */
public class PaymentReferenceInUseException extends RuntimeException {

    private final String reference;

    public PaymentReferenceInUseException(String reference, Throwable cause) {
        super("A payment is already recorded under reference '" + reference + "'.", cause);
        this.reference = reference;
    }

    public String getReference() {
        return reference;
    }
}
