package com.invo.coopr8.dto.config;

import java.time.LocalDateTime;

import com.invo.coopr8.model.OrganizationLoanTypeExclusion;

/**
 * One "these two products are mutually exclusive" rule, as an administrator reads it back.
 *
 * <p>The two product names are carried alongside the ids because the row itself stores only ids,
 * and an admin screen listing {@code 3 / 7} is unreadable. They are resolved by the service from
 * the caller's own cooperative, so a name here can only ever be one this administrator may see.
 *
 * <p>{@code loanTypeId} is always the lower of the two ids -- that is how the row is normalized so
 * that {@code {3,7}} and {@code {7,3}} cannot both exist. Callers must not read an ordering into
 * it: neither side excludes the other more than the reverse.
 *
 * <p>No organization field -- see {@link LoanConfigResponse}.
 */
public record LoanTypeExclusionResponse(
        Long id,
        Long loanTypeId,
        String loanTypeName,
        Long excludedLoanTypeId,
        String excludedLoanTypeName,
        LocalDateTime createdAt) {

    public static LoanTypeExclusionResponse of(OrganizationLoanTypeExclusion exclusion,
            String loanTypeName, String excludedLoanTypeName) {
        return new LoanTypeExclusionResponse(
                exclusion.getId(),
                exclusion.getLoanTypeId(),
                loanTypeName,
                exclusion.getExcludedLoanTypeId(),
                excludedLoanTypeName,
                exclusion.getCreatedAt());
    }
}
