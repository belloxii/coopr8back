package com.invo.coopr8.dto.config;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One loan product a cooperative offers.
 *
 * <p>Every rule here is optional except the name and {@code maxActiveLoans}: a null
 * {@code interestMethod}, {@code interestRate} or bound means "whatever this cooperative's own
 * loan configuration says". That is what makes a product a variation on the cooperative's rules
 * rather than a second copy of them.
 *
 * <p>No organization field -- see {@link LoanConfigRequest}.
 *
 * @param active         null on create means active. Products are never hard-deleted:
 *                       {@code DELETE} sets this false, because a loan issued under a product
 *                       still refers to it.
 * @param maxActiveLoans how many of this product one member may hold at once. Zero is meaningful
 *                       -- "defined, but not currently on offer".
 */
public record LoanTypeRequest(

        @NotBlank(message = "Give this loan product a name.")
        @Size(max = 255, message = "Keep the name under 255 characters.")
        String name,

        @Size(max = 512, message = "Keep the description under 512 characters.")
        String description,

        String interestMethod,

        @DecimalMin(value = "0.000", message = "The annual interest rate cannot be negative.")
        @Digits(integer = 3, fraction = 3,
                message = "The annual interest rate may have at most 3 whole digits and 3 decimals.")
        BigDecimal interestRate,

        @DecimalMin(value = "0.00", message = "The minimum loan amount cannot be negative.")
        @Digits(integer = 36, fraction = 2, message = "That minimum loan amount is too large.")
        BigDecimal minLoanAmount,

        @DecimalMin(value = "0.00", message = "The maximum loan amount cannot be negative.")
        @Digits(integer = 36, fraction = 2, message = "That maximum loan amount is too large.")
        BigDecimal maxLoanAmount,

        @Min(value = 1, message = "The minimum tenure must be at least one month.")
        @Max(value = 600, message = "The minimum tenure cannot exceed 600 months.")
        Integer minTenureMonths,

        @Min(value = 1, message = "The maximum tenure must be at least one month.")
        @Max(value = 600, message = "The maximum tenure cannot exceed 600 months.")
        Integer maxTenureMonths,

        @NotNull(message = "State how many of this loan a member may hold at once.")
        @Min(value = 0, message = "That count cannot be negative.")
        @Max(value = 99, message = "That count cannot exceed 99.")
        Integer maxActiveLoans,

        Boolean active,

        @Size(max = 512, message = "Keep the reason under 512 characters.")
        String reason) {
}
