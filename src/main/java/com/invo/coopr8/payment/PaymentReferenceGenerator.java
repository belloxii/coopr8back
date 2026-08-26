package com.invo.coopr8.payment;

import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Mints the reference a payment is known by, on both sides.
 *
 * <p>COOPR8 generates the reference and tells the provider what it is, rather than accepting one the
 * provider mints. That ordering is what closes the gap the old flow had: the authoritative payment row
 * exists, with its cooperative and its member, before the provider is called at all -- so there is no
 * window in which a callback could arrive naming a payment COOPR8 has no record of.
 *
 * <p><strong>A reference carries no meaning.</strong> It does not encode the organization, the member
 * or the amount. It appears in a provider dashboard and travels through a browser, and anything
 * legible in it is something a reader might be tempted to trust. The database row is the mapping; the
 * reference is only a key into it.
 *
 * <p>Not {@link com.invo.coopr8.utils.TxnIdGen}, which formats the current minute and three random
 * digits: a thousand possibilities within a minute collides in ordinary use, and this value backs a
 * uniqueness constraint that a collision would turn into a failed payment.
 */
@Component
public class PaymentReferenceGenerator {

    /** Distinguishes COOPR8's references at a glance in a provider dashboard. */
    private static final String PREFIX = "C8-";

    /**
     * A fresh reference.
     *
     * <p>Hyphenated hexadecimal, so it stays inside the character set payment providers accept for
     * merchant-supplied references (alphanumerics, {@code -}, {@code .}, {@code =}).
     */
    public String generate() {
        return PREFIX + UUID.randomUUID();
    }
}
