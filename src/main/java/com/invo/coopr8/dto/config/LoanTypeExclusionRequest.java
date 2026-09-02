package com.invo.coopr8.dto.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * "A member holding one of these two loan products may not take the other."
 *
 * <p>The pair is unordered: posting {@code {3, 7}} and {@code {7, 3}} describe the same rule, and
 * the second is refused as a duplicate. Normalization -- lower id first -- happens inside
 * {@code OrganizationLoanTypeExclusion.between}, so no caller has to know which id is the smaller
 * one.
 *
 * <p>Both ids are looked up with {@code findByIdAndOrganizationId} against the caller's own
 * cooperative before a row is written, so an id belonging to another cooperative is a 404 rather
 * than a rule spanning two tenants.
 *
 * <p>No organization field -- see {@link LoanConfigRequest}.
 */
public record LoanTypeExclusionRequest(

        @NotNull(message = "Name the first loan product.")
        Long loanTypeId,

        @NotNull(message = "Name the loan product it excludes.")
        Long excludedLoanTypeId,

        @Size(max = 512, message = "Keep the reason under 512 characters.")
        String reason) {
}
