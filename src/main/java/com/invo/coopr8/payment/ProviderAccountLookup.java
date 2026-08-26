package com.invo.coopr8.payment;

/**
 * A settlement destination to look for among the accounts a provider already holds.
 *
 * @param bankCode      the settlement bank, in the provider's vocabulary.
 * @param accountNumber the settlement account number. This is the identifying half: account numbers
 *                      are bank-scoped, so a match on the number alone is a strong candidate but not
 *                      a proof, which is why the search can answer "ambiguous".
 */
public record ProviderAccountLookup(String bankCode, String accountNumber) {
}
