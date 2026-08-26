package com.invo.coopr8.model;

/**
 * Whether a payment COOPR8 started has been credited.
 *
 * <p>There are only two states, and the transition between them is the platform's idempotency
 * guarantee. It is performed as a conditional update -- {@code SET status = 'SUCCEEDED' WHERE
 * status = 'PENDING'} -- inside the same transaction as the credit itself, so the row that says
 * "credited" and the money that was credited commit together. A replayed provider event finds the
 * update affecting no rows and does nothing.
 *
 * <p>There is deliberately no {@code FAILED}. A payment that the provider never confirms simply
 * stays {@link #PENDING}: COOPR8 does not learn from a provider's silence, and a row marked failed
 * on a guess is a row a later genuine confirmation could not claim.
 */
public enum PaymentStatus {

    /** Started, not credited. The state every payment is created in. */
    PENDING,

    /** Credited, exactly once. {@code processed_at} records when. */
    SUCCEEDED
}
