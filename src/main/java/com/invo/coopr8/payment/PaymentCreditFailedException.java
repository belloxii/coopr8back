package com.invo.coopr8.payment;

/**
 * A confirmed payment could not be credited to the member's balance.
 *
 * <p>Unchecked on purpose. The claim that marks a payment posted and the credit that moves the
 * balance share one transaction; this exception is what rolls both back together, so a provider that
 * retries the callback finds the payment still unposted and can succeed. Were it checked, Spring's
 * default rules would commit the claim and the payment would be marked paid with nothing credited.
 *
 * <p>It is allowed to reach the caller and become a {@code 500}: a provider treats that as a failed
 * delivery and retries, and a payment that keeps failing shows up as a failed webhook in the
 * provider's own dashboard, which is the signal an operator needs.
 */
public class PaymentCreditFailedException extends RuntimeException {

    public PaymentCreditFailedException(Long paymentTransactionId, Throwable cause) {
        super("Payment " + paymentTransactionId + " was confirmed but could not be credited.", cause);
    }
}
