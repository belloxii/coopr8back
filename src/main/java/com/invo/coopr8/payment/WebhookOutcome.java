package com.invo.coopr8.payment;

/**
 * What COOPR8 did with a provider callback.
 *
 * <p>Every value here except {@link #REJECTED} answers the provider with {@code 200}. A provider
 * retries anything that is not a success, so answering an error to a callback that will never
 * succeed -- an unknown reference, an event about something else -- buys nothing but a retry storm.
 * The distinction that matters operationally is in the log line, not the status code.
 *
 * <p>A failure that <em>might</em> succeed on retry is deliberately not in this enum: it propagates
 * as an exception, rolls the claim back, and becomes a {@code 500} the provider will try again.
 */
public enum WebhookOutcome {

    /** The signature was missing or wrong. Nothing was parsed and nothing was touched. */
    REJECTED,

    /** A payment was recorded and the member's balance moved. */
    PROCESSED,

    /**
     * The callback named a payment that had already been processed. No second mutation. This is the
     * ordinary outcome of a provider retry and is not a problem.
     */
    DUPLICATE,

    /** Authentic, but about something COOPR8 does not act on -- any event but a successful charge. */
    IGNORED,

    /**
     * Authentic, but names a reference COOPR8 has no payment for.
     *
     * <p>This should be impossible: the payment row is committed before the provider is ever asked
     * to start the checkout. It is logged as an error rather than ignored, because it would mean
     * either a reference COOPR8 never minted or a row that vanished.
     */
    UNKNOWN_REFERENCE,

    /**
     * Authentic and known, but the provider's own verification does not agree that the money was
     * taken, or does not agree about how much. Left unprocessed for a person to look at rather than
     * credited on the strength of the callback alone.
     */
    UNCONFIRMED
}
