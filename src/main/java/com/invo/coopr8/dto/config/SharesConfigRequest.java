package com.invo.coopr8.dto.config;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A cooperative's share rules.
 *
 * <p>No organization field -- see {@link LoanConfigRequest}.
 *
 * @param sharePrice          the naira value of one share unit. A price, so changing it requires a
 *                            {@code reason}. V9 seeds 1.00 for every existing cooperative because
 *                            the {@code shares} table holds a naira amount and has no unit
 *                            concept, and 1.00 is the only price that keeps that true.
 * @param minWithdrawalAmount null means "no minimum", not zero.
 */
public record SharesConfigRequest(

        @NotNull(message = "State the price of one share.")
        @DecimalMin(value = "0.00", inclusive = false,
                message = "The share price must be greater than zero.")
        @Digits(integer = 36, fraction = 2, message = "That share price is too large.")
        BigDecimal sharePrice,

        @DecimalMin(value = "0.00", message = "The minimum purchase cannot be negative.")
        @Digits(integer = 36, fraction = 2, message = "That minimum purchase is too large.")
        BigDecimal minPurchaseAmount,

        @DecimalMin(value = "0.00", message = "The maximum purchase cannot be negative.")
        @Digits(integer = 36, fraction = 2, message = "That maximum purchase is too large.")
        BigDecimal maxPurchaseAmount,

        @NotNull(message = "Say whether a share purchase needs administrator approval.")
        Boolean approvalRequired,

        @NotNull(message = "Say whether members may withdraw shares.")
        Boolean withdrawalAllowed,

        @DecimalMin(value = "0.00", message = "The minimum withdrawal cannot be negative.")
        @Digits(integer = 36, fraction = 2, message = "That minimum withdrawal is too large.")
        BigDecimal minWithdrawalAmount,

        @Size(max = 512, message = "Keep the reason under 512 characters.")
        String reason) {
}
