package com.invo.coopr8.dto.config;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.invo.coopr8.model.OrganizationRepaymentConfig;

/**
 * A cooperative's repayment rules, as an administrator reads them back.
 *
 * <p>No penalty field, by Decision 6. No organization field.
 */
public record RepaymentConfigResponse(
        Long id,
        Boolean allowPartialRepayment,
        Boolean allowOverpayment,
        BigDecimal settlementTolerance,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static RepaymentConfigResponse of(OrganizationRepaymentConfig configuration) {
        return new RepaymentConfigResponse(
                configuration.getId(),
                configuration.getAllowPartialRepayment(),
                configuration.getAllowOverpayment(),
                configuration.getSettlementTolerance(),
                configuration.getCreatedAt(),
                configuration.getUpdatedAt());
    }
}
