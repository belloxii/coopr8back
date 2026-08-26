package com.invo.coopr8.model;

/**
 * How a cooperative's provider account is held.
 *
 * <p>Provider-neutral on purpose. "Subaccount" is Paystack's word for the thing
 * {@link #PLATFORM_SUBACCOUNT} describes, and a different provider may call the same arrangement
 * something else. What the domain needs to know is not the provider's noun but the shape of the
 * arrangement: whether the account sits under COOPR8's own merchant account and is settled by a
 * split, or whether there is no destination at all.
 */
public enum PaymentAccountMode {

    /**
     * No provider account. Online payment is not payable in this state -- initialization refuses
     * rather than falling back to settling into the platform's own account, because money settled
     * into the platform's account is money the cooperative then has to be paid out of band.
     */
    NONE,

    /**
     * The account sits under COOPR8's platform merchant account, and the provider splits
     * settlement to it. This is the mode Paystack subaccounts implement, and the only mode with an
     * implementation today.
     */
    PLATFORM_SUBACCOUNT
}
