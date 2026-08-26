package com.invo.coopr8.payment;

/**
 * Where to send a member to complete a payment.
 *
 * @param providerReference the reference the payment is known by. Echoed back so a caller can assert
 *                          the provider accepted the reference it was given rather than substituting
 *                          one of its own.
 * @param checkoutUrl       the hosted page the member completes the payment on.
 * @param accessCode        the provider's handle for the same checkout, for clients that embed
 *                          rather than redirect. May be null.
 */
public record ProviderCheckoutResult(
        String providerReference,
        String checkoutUrl,
        String accessCode) {
}
