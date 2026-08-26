package com.invo.coopr8.payment;

import java.math.BigDecimal;

/**
 * What the provider says actually happened to a payment, read over an authenticated call.
 *
 * <p>This is the only statement about money COOPR8 credits from. A signed callback body is a
 * notification; this is the confirmation.
 *
 * @param providerReference the payment this describes.
 * @param paid              whether the provider confirms the money was taken.
 * @param amount            what was actually taken, in major units (naira), or null when the
 *                          provider reports none.
 */
public record ProviderVerificationResult(
        String providerReference,
        boolean paid,
        BigDecimal amount) {

    /** A payment the provider does not confirm. Nothing is credited on this. */
    public static ProviderVerificationResult unpaid(String providerReference) {
        return new ProviderVerificationResult(providerReference, false, null);
    }
}
