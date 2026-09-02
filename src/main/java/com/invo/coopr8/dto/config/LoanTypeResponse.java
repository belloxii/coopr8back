package com.invo.coopr8.dto.config;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.invo.coopr8.model.OrganizationLoanType;

/**
 * One loan product, as an administrator reads it back.
 *
 * <p>A null {@code interestMethod}, {@code interestRate} or bound is meaningful and is returned as
 * null rather than filled in from the cooperative-wide configuration: the admin screen has to be
 * able to show "inherited" differently from "set to the same value as the default", because the
 * two behave differently the moment the default changes.
 *
 * <p>No organization field -- see {@link LoanConfigResponse}.
 */
public record LoanTypeResponse(
        Long id,
        String name,
        String description,
        String interestMethod,
        BigDecimal interestRate,
        BigDecimal minLoanAmount,
        BigDecimal maxLoanAmount,
        Integer minTenureMonths,
        Integer maxTenureMonths,
        Integer maxActiveLoans,
        Boolean active,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static LoanTypeResponse of(OrganizationLoanType loanType) {
        return new LoanTypeResponse(
                loanType.getId(),
                loanType.getName(),
                loanType.getDescription(),
                loanType.getInterestMethod() == null ? null : loanType.getInterestMethod().name(),
                loanType.getInterestRate(),
                loanType.getMinLoanAmount(),
                loanType.getMaxLoanAmount(),
                loanType.getMinTenureMonths(),
                loanType.getMaxTenureMonths(),
                loanType.getMaxActiveLoans(),
                loanType.getActive(),
                loanType.getCreatedAt(),
                loanType.getUpdatedAt());
    }
}
