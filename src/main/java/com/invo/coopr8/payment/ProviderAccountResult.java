package com.invo.coopr8.payment;

import com.invo.coopr8.model.PaymentProviderName;

/**
 * The account a provider now holds for a cooperative.
 *
 * <p>Deliberately narrow. A provider's account-creation response carries a great deal more --
 * internal ids, fee arrangements, the merchant's own details -- and none of it belongs in the domain.
 * What COOPR8 needs is a handle it can settle to and a statement of whether it is usable.
 *
 * @param provider                 which provider issued it.
 * @param providerAccountReference the handle. Paystack: {@code ACCT_xxxxxxxx}.
 * @param active                   whether the provider reports the account as usable.
 */
public record ProviderAccountResult(
        PaymentProviderName provider,
        String providerAccountReference,
        boolean active) {
}
