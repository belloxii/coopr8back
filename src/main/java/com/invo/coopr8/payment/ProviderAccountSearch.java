package com.invo.coopr8.payment;

/**
 * The result of looking for an account a provider may already hold for a settlement destination.
 *
 * <p><strong>Four outcomes, not an {@code Optional}, and that is the point of this type.</strong>
 * Provider account creation has no idempotency key -- there is no header COOPR8 can send that makes
 * "create this account" safe to repeat. So the only safe way to retry an interrupted setup is to
 * look first. And looking has two different kinds of "no": <em>I searched everything and it is not
 * there</em>, after which creating is safe, and <em>I could not search everything</em>, after which
 * creating is a coin flip that risks a second account holding the same cooperative's money. An
 * {@code Optional} would render both as {@code empty} and the caller would treat them alike.
 *
 * @param outcome                  what the search established.
 * @param providerAccountReference the account found, and null for every outcome but
 *                                 {@link Outcome#FOUND}.
 */
public record ProviderAccountSearch(Outcome outcome, String providerAccountReference) {

    /** What a search established. */
    public enum Outcome {

        /** Exactly one account settles to this destination. Adopt it; create nothing. */
        FOUND,

        /**
         * The provider's accounts were enumerated in full and none settles to this destination.
         * Creating one is safe.
         */
        NONE,

        /**
         * More than one account settles to this destination. Creating another would compound the
         * problem, and picking one would be a guess about where a cooperative's money should go.
         * A person has to look.
         */
        AMBIGUOUS,

        /**
         * The search could not be completed -- the provider refused, or holds more accounts than
         * the enumeration will read. Nothing may be created on this outcome: the account being
         * looked for might exist just past where the search stopped.
         */
        INDETERMINATE
    }

    public static ProviderAccountSearch found(String providerAccountReference) {
        return new ProviderAccountSearch(Outcome.FOUND, providerAccountReference);
    }

    public static ProviderAccountSearch none() {
        return new ProviderAccountSearch(Outcome.NONE, null);
    }

    public static ProviderAccountSearch ambiguous() {
        return new ProviderAccountSearch(Outcome.AMBIGUOUS, null);
    }

    public static ProviderAccountSearch indeterminate() {
        return new ProviderAccountSearch(Outcome.INDETERMINATE, null);
    }
}
