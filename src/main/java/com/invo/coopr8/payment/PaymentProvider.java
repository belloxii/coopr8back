package com.invo.coopr8.payment;

import java.util.Optional;

import com.invo.coopr8.model.PaymentProviderName;

/**
 * Everything COOPR8 needs from a payment provider, stated without reference to any particular one.
 *
 * <p><strong>This interface is the boundary.</strong> No class outside
 * {@code com.invo.coopr8.payment.paystack} names a Paystack type -- not the services, not the
 * controllers, not the entities -- and {@code TenantIsolationArchitectureTest} fails the build if one
 * starts to. Adding Flutterwave later is therefore: write a second implementation, register it as a
 * bean, add the enum value to two CHECK constraints. Nothing in the tenant or payment domain changes.
 *
 * <p><strong>Neutral in both directions.</strong> Arguments and return values are records defined in
 * this package. A provider's own request and response shapes stop at its adapter -- an implementation
 * that returned, say, a Paystack response object would put every caller back in the business of
 * knowing Paystack's field names.
 *
 * <p><strong>Amounts are in major units</strong> (naira), always. Providers that transact in minor
 * units convert inside their own adapter, because "is this figure kobo or naira" is exactly the kind
 * of question that goes wrong once and then goes wrong quietly.
 *
 * <p>Implementations throw {@link PaymentProviderException} when the provider cannot be reached or
 * answers in a way that cannot be believed. They do not return a null result to mean failure.
 */
public interface PaymentProvider {

    /** Which provider this is. The registry keys on this; nothing else should switch on it. */
    PaymentProviderName providerName();

    // ------------------------------------------------------------------ taking a payment

    /**
     * Starts a payment and returns where to send the member to complete it.
     *
     * <p>The reference in the request is COOPR8's, already recorded against a cooperative and a
     * member before this call is made. An implementation must submit that reference to the provider
     * rather than let the provider mint its own, so that the callback names a payment COOPR8 already
     * knows.
     */
    ProviderCheckoutResult initializeCheckout(ProviderCheckoutRequest request);

    /**
     * Asks the provider, over an authenticated call, what actually happened to a payment.
     *
     * <p>This is the only statement about money COOPR8 acts on. A callback body -- even one with a
     * valid signature -- says only "come and look"; this is the looking.
     */
    ProviderVerificationResult verifyPayment(String providerReference);

    // ------------------------------------------------------- the cooperative's own account

    /**
     * Creates a provider account that settles to the cooperative's own bank account.
     *
     * <p>Called only after {@link #findTenantAccount} has established that no such account exists
     * yet -- see {@code TenantPaymentSetupService} for why that order is not optional.
     */
    ProviderAccountResult createTenantAccount(ProviderAccountRequest request);

    /**
     * Looks for an account the provider already holds for this settlement destination.
     *
     * <p>Exists because provider account creation cannot be retried safely on its own. The outcome
     * is deliberately four-valued rather than an {@code Optional}: "I searched and found nothing" and
     * "I could not search properly" must not collapse into the same answer, because the first makes
     * creation safe and the second makes it a coin flip. See {@link ProviderAccountSearch}.
     */
    ProviderAccountSearch findTenantAccount(ProviderAccountLookup lookup);

    // ------------------------------------------------------------------- the callback

    /**
     * Whether {@code rawBody} carries a signature this provider genuinely produced.
     *
     * <p>Takes the exact bytes received. Verification is meaningless against a re-serialized body:
     * a signature covers the bytes the provider signed, and any parse-then-print round trip may
     * produce different ones. Implementations must compare in constant time and must treat a missing
     * or malformed signature as a failure, never as an absent check.
     */
    boolean signatureMatches(byte[] rawBody, String signatureHeader);

    /**
     * The provider reference named by {@code rawBody} when it is a successful-payment notification,
     * and empty for anything else -- a different event, an unparseable body, a body with no
     * reference in it.
     *
     * <p>Only ever called on a body whose signature has already been verified. What comes back is a
     * reference to look up, not a fact about money; the fact comes from {@link #verifyPayment}.
     */
    Optional<String> successfulPaymentReference(byte[] rawBody);
}
