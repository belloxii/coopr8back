package com.invo.coopr8.payment;

/**
 * A request to create the provider account a cooperative's money settles into.
 *
 * <p>Every field describes a <em>destination</em>. None of them is a credential: a cooperative never
 * supplies provider API keys, and COOPR8 would have nowhere to put them if it did.
 *
 * @param businessName  the cooperative's own name, as it should appear to the provider.
 * @param bankCode      the settlement bank in the provider's bank-code vocabulary.
 * @param accountNumber the settlement account number.
 * @param accountName   the account name as the bank holds it. May be null when the provider resolves
 *                      it itself.
 * @param contactEmail  where the provider addresses account correspondence.
 */
public record ProviderAccountRequest(
        String businessName,
        String bankCode,
        String accountNumber,
        String accountName,
        String contactEmail) {
}
