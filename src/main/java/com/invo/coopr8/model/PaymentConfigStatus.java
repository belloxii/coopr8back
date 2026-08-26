package com.invo.coopr8.model;

/**
 * Whether a cooperative's payment configuration may be used to take money.
 *
 * <p>Only {@link #ACTIVE} is payable, and {@code ck_organization_payment_config_active_reference}
 * in V12 will not let a row reach {@code ACTIVE} without an account reference to settle into. So
 * "the provider call failed" and "the cooperative is connected" are mutually exclusive states in
 * the database, not merely in the service that writes it.
 */
public enum PaymentConfigStatus {

    /**
     * Set up, but not connected. Either the provider has not been asked for an account yet, or it
     * was asked and the answer never arrived. Either way this is the safe resting state: it is not
     * payable, and it carries the settlement details needed to reconcile against the provider
     * rather than retry blindly into a second account.
     */
    PENDING,

    /** Connected and payable. */
    ACTIVE,

    /** Deliberately switched off. Not payable. */
    DISABLED
}
