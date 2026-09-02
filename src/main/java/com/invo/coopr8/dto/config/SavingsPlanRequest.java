package com.invo.coopr8.dto.config;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One savings plan a cooperative offers.
 *
 * <p>{@code frequency} is a String rather than {@code SavingsFrequency}, for the reason given on
 * {@link LoanConfigRequest}: an unknown value has to come back as a sentence, not as an unhandled
 * deserialization failure.
 *
 * <p>No organization field -- see {@link LoanConfigRequest}.
 *
 * @param planAmount the contribution due each period. A price, so changing it requires a
 *                   {@code reason}.
 * @param minAmount  null means "no lower bound", not zero.
 */
public record SavingsPlanRequest(

        @NotBlank(message = "Give this savings plan a name.")
        @Size(max = 255, message = "Keep the name under 255 characters.")
        String name,

        @Size(max = 512, message = "Keep the description under 512 characters.")
        String description,

        @NotNull(message = "State the contribution amount.")
        @DecimalMin(value = "0.00", inclusive = false,
                message = "The contribution amount must be greater than zero.")
        @Digits(integer = 36, fraction = 2, message = "That contribution amount is too large.")
        BigDecimal planAmount,

        @NotBlank(message = "Choose how often the contribution is due.")
        String frequency,

        @DecimalMin(value = "0.00", message = "The minimum amount cannot be negative.")
        @Digits(integer = 36, fraction = 2, message = "That minimum amount is too large.")
        BigDecimal minAmount,

        @DecimalMin(value = "0.00", message = "The maximum amount cannot be negative.")
        @Digits(integer = 36, fraction = 2, message = "That maximum amount is too large.")
        BigDecimal maxAmount,

        Boolean active,

        @Size(max = 512, message = "Keep the reason under 512 characters.")
        String reason) {
}
