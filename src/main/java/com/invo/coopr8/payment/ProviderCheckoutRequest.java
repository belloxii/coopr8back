package com.invo.coopr8.payment;

import java.math.BigDecimal;
import java.util.Map;

/**
 * A request to start one payment, stated in COOPR8's terms.
 *
 * @param providerReference       the reference COOPR8 generated and has already recorded against a
 *                                cooperative and a member. The provider is told this reference
 *                                rather than asked to mint one.
 * @param amount                  in major units (naira). The provider's adapter converts.
 * @param payerEmail              the member's own address, read from their record -- never from the
 *                                request body, which is why a browser cannot make a receipt go
 *                                somewhere else.
 * @param callbackUrl             where the provider returns the member's browser afterwards.
 * @param settlementAccountReference the provider's handle for the destination this payment settles
 *                                to, resolved from the paying member's own cooperative. Null only
 *                                when the provider is being used without a split.
 * @param metadata                assembled entirely from values COOPR8 has already validated. It is
 *                                not the caller's metadata passed through: the provider dashboard is
 *                                a place cooperative staff read, and what appears there should be
 *                                what COOPR8 believes rather than what a browser sent.
 */
public record ProviderCheckoutRequest(
        String providerReference,
        BigDecimal amount,
        String payerEmail,
        String callbackUrl,
        String settlementAccountReference,
        Map<String, String> metadata) {
}
