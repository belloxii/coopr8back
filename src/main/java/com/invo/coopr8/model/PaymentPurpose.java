package com.invo.coopr8.model;

import java.util.Locale;
import java.util.Optional;

/**
 * What a payment is for, and therefore which ledger a verified payment credits.
 *
 * <p><strong>Resolution is strict.</strong> {@link PaymentType#fromValue(String)} is deliberately
 * lenient because an unrecognised payment type there means "treat this member as self-paying", which
 * is the safe reading. Here leniency would be the opposite: an unrecognised purpose defaulted to
 * anything would credit a member's savings for money they meant to put into shares, or repay a loan
 * with money meant for savings. So {@link #fromRequestType(String)} returns nothing for a value it
 * does not recognise, and the caller refuses the payment before it starts.
 *
 * <p>The three names match the {@code metadata.type} values the portal already sends
 * ({@code savings}, {@code repayment}, {@code shares}), which is why they are what they are.
 */
public enum PaymentPurpose {

    /** Credits the member's savings balance and writes a {@code saving} row. */
    SAVINGS,

    /** Pays down one named loan. The loan is the payment's {@code targetId}. */
    REPAYMENT,

    /** Credits the member's shares balance and writes a {@code shares} row. */
    SHARES;

    /**
     * The purpose named by the portal's {@code metadata.type}, or empty when it names none.
     *
     * <p>Empty is a refusal, not a default -- see the class comment.
     */
    public static Optional<PaymentPurpose> fromRequestType(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }

        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "savings" -> Optional.of(SAVINGS);
            case "repayment" -> Optional.of(REPAYMENT);
            case "shares" -> Optional.of(SHARES);
            default -> Optional.empty();
        };
    }

    /** The {@code metadata.type} value the portal uses for this purpose. */
    public String requestType() {
        return name().toLowerCase(Locale.ROOT);
    }
}
