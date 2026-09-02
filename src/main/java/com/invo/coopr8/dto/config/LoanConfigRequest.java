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
 * A cooperative's loan rules, as an administrator submits them.
 *
 * <p><strong>There is no organization field, and that is the point.</strong> The tenant comes from
 * the caller's verified token; a request body that cannot carry a tenant cannot smuggle one. See
 * {@code ConfigurationRequestSurfaceTest}, which fails the build if any configuration request DTO
 * grows an {@code organizationId}, {@code organization} or {@code tenant} field.
 *
 * <p><strong>{@code interestMethod} is a String rather than the enum.</strong> Binding straight to
 * {@code InterestMethod} would turn {@code MONTHLY_COMPOUND} into a Jackson
 * {@code HttpMessageNotReadableException}, which {@code DomainExceptionHandler} deliberately does
 * not handle -- the administrator would get a bare 400 and no explanation. It is parsed in the
 * service instead, so an unknown or non-selectable method is refused with a sentence naming what
 * is allowed.
 *
 * <p><strong>The numeric bounds are the schema's, not invented business rules.</strong>
 * {@code interest_rate} is {@code numeric(6,3)} and the money columns are {@code numeric(38,2)};
 * the annotations say so, so an over-long value is refused with a message instead of failing at
 * the INSERT.
 *
 * @param interestRate       per annum. Decision 2 in {@code docs/phase4-open-decisions.md}: there
 *                           is no rate-basis column, the basis is fixed at annual, and the admin
 *                           UI must label this field "Annual Interest Rate (% per year)".
 * @param minLoanAmount      null means "no lower bound". Zero would mean a bound of zero, which
 *                           forbids nothing but reads as if it did.
 * @param requiredGuarantors 0, 1 or 2. Two is the ceiling because {@code Loan} has exactly two
 *                           guarantor columns -- a third would be a schema change, not a setting.
 * @param reason             mandatory when {@code interestRate} changes, optional otherwise.
 */
public record LoanConfigRequest(

        @NotBlank(message = "Choose an interest method.")
        String interestMethod,

        @NotNull(message = "State the annual interest rate. Use 0 for interest-free loans.")
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

        @NotNull(message = "State how many guarantors a loan requires.")
        @Min(value = 0, message = "A loan cannot require a negative number of guarantors.")
        @Max(value = 2, message = "COOPR8 records at most two guarantors per loan.")
        Integer requiredGuarantors,

        @Size(max = 512, message = "Keep the reason under 512 characters.")
        String reason) {
}
