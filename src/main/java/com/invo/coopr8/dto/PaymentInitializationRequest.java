package com.invo.coopr8.dto;

import java.util.Map;

import lombok.Data;

/**
 * A member asking to pay something online.
 *
 * <p><strong>Provider-neutral, and deliberately narrow about what it is allowed to decide.</strong>
 * There is no field for a settlement account, a subaccount code, an organization or a payment
 * provider, and adding one would be a mistake: the destination of a payment is derived from the
 * authenticated member's own cooperative, and a browser that could name it could name a different
 * cooperative's.
 *
 * <p>Two fields are accepted and then ignored, because the frontend already sends them:
 * <ul>
 *   <li>{@link #email} -- the payer is the authenticated caller. An email here was previously used
 *       as-sent, which let a member start a payment under somebody else's address.</li>
 *   <li>{@code metadata} beyond {@code type} and {@code loanId} -- nothing else is read, and nothing
 *       from here is forwarded to the provider.</li>
 * </ul>
 *
 * <p>The field names and shape match what the existing frontend posts, including
 * {@link #callback_url}'s underscore and {@link #amount}'s unit.
 */
@Data
public class PaymentInitializationRequest {

    /** Ignored. The payer is whoever the token says they are. */
    private String email;

    /**
     * The amount, in <strong>kobo</strong> -- the naira's minor unit, as the frontend already sends
     * it. Converted to naira before it reaches anything that understands money.
     */
    private long amount;

    /** Where the payer's browser should return after the provider's checkout. */
    private String callback_url;

    /**
     * What this payment is for. Only {@code type} ({@code savings}, {@code repayment} or
     * {@code shares}) and, for a repayment, {@code loanId} are read. Neither can select another
     * member's loan: the repayment path scopes the loan to this member within their own cooperative.
     */
    private Map<String, Object> metadata;
}
