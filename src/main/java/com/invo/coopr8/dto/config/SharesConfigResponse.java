package com.invo.coopr8.dto.config;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.invo.coopr8.model.OrganizationSharesConfig;

/** A cooperative's share rules, as an administrator reads them back. No organization field. */
public record SharesConfigResponse(
        Long id,
        BigDecimal sharePrice,
        BigDecimal minPurchaseAmount,
        BigDecimal maxPurchaseAmount,
        Boolean approvalRequired,
        Boolean withdrawalAllowed,
        BigDecimal minWithdrawalAmount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static SharesConfigResponse of(OrganizationSharesConfig configuration) {
        return new SharesConfigResponse(
                configuration.getId(),
                configuration.getSharePrice(),
                configuration.getMinPurchaseAmount(),
                configuration.getMaxPurchaseAmount(),
                configuration.getApprovalRequired(),
                configuration.getWithdrawalAllowed(),
                configuration.getMinWithdrawalAmount(),
                configuration.getCreatedAt(),
                configuration.getUpdatedAt());
    }
}
