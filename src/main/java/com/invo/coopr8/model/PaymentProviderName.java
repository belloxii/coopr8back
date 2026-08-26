package com.invo.coopr8.model;

/**
 * A payment provider COOPR8 can settle money through.
 *
 * <p><strong>Listing a provider here does not make it supported.</strong> Support is decided by
 * which {@code PaymentProvider} beans exist: {@code PaymentProviderRegistry} resolves this enum to
 * an implementation and refuses -- loudly, at the point of use -- when there is none. Today exactly
 * one value has an implementation, {@link #PAYSTACK}.
 *
 * <p>{@link #FLUTTERWAVE} is present because the persisted value is a database CHECK constraint and
 * a stored column: adding a value to a CHECK later is a migration, and the domain reads better with
 * a second name in it than with an enum that has one member and pretends to be a choice. Nothing in
 * COOPR8 claims Flutterwave works -- no endpoint offers it, no bean implements it, and an attempt to
 * use it fails rather than degrading to Paystack.
 */
public enum PaymentProviderName {

    /** Paystack. The first and, today, only implemented provider. */
    PAYSTACK,

    /**
     * Flutterwave. <strong>No implementation exists.</strong> Selecting it fails closed; it is not
     * offered anywhere and must not be presented to a cooperative as available.
     */
    FLUTTERWAVE
}
