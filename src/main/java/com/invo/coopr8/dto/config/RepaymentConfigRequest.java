package com.invo.coopr8.dto.config;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A cooperative's repayment rules.
 *
 * <p><strong>There is no penalty setting here, and there must never be one.</strong> Decision 6 in
 * {@code docs/phase4-open-decisions.md} defers penalties entirely: no column, no field, no
 * setting. Two existing tests fail the build if one appears.
 *
 * <p>No organization field -- see {@link LoanConfigRequest}.
 *
 * @param settlementTolerance how far short of the exact figure a repayment may fall and still
 *                            settle the instalment. V9 seeds 0.00, which reproduces today's
 *                            behaviour: {@code RepayServiceImpl} requires an exact multiple.
 */
public record RepaymentConfigRequest(

        @NotNull(message = "Say whether part-payments are accepted.")
        Boolean allowPartialRepayment,

        @NotNull(message = "Say whether overpayments are accepted.")
        Boolean allowOverpayment,

        @NotNull(message = "State the settlement tolerance. Use 0 to require the exact amount.")
        @DecimalMin(value = "0.00", message = "The settlement tolerance cannot be negative.")
        @Digits(integer = 36, fraction = 2, message = "That settlement tolerance is too large.")
        BigDecimal settlementTolerance,

        @Size(max = 512, message = "Keep the reason under 512 characters.")
        String reason) {
}
