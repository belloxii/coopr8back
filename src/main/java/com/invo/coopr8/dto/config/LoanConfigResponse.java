package com.invo.coopr8.dto.config;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.invo.coopr8.model.OrganizationLoanConfig;

/**
 * A cooperative's loan rules, as an administrator reads them back.
 *
 * <p>No organization field. It would be the caller's own tenant and therefore harmless, but a
 * response is what a frontend later echoes into a request, and the shortest way to guarantee no
 * request ever carries a tenant is for no configuration payload to mention one.
 */
public record LoanConfigResponse(
        Long id,
        String interestMethod,
        BigDecimal interestRate,
        BigDecimal minLoanAmount,
        BigDecimal maxLoanAmount,
        Integer minTenureMonths,
        Integer maxTenureMonths,
        Integer requiredGuarantors,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static LoanConfigResponse of(OrganizationLoanConfig configuration) {
        return new LoanConfigResponse(
                configuration.getId(),
                configuration.getInterestMethod() == null
                        ? null : configuration.getInterestMethod().name(),
                configuration.getInterestRate(),
                configuration.getMinLoanAmount(),
                configuration.getMaxLoanAmount(),
                configuration.getMinTenureMonths(),
                configuration.getMaxTenureMonths(),
                configuration.getRequiredGuarantors(),
                configuration.getCreatedAt(),
                configuration.getUpdatedAt());
    }
}
