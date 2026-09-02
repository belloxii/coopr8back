package com.invo.coopr8.dto.config;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.invo.coopr8.model.OrganizationSavingsPlan;

/** One savings plan, as an administrator reads it back. No organization field. */
public record SavingsPlanResponse(
        Long id,
        String name,
        String description,
        BigDecimal planAmount,
        String frequency,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        Boolean active,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static SavingsPlanResponse of(OrganizationSavingsPlan plan) {
        return new SavingsPlanResponse(
                plan.getId(),
                plan.getName(),
                plan.getDescription(),
                plan.getPlanAmount(),
                plan.getFrequency() == null ? null : plan.getFrequency().name(),
                plan.getMinAmount(),
                plan.getMaxAmount(),
                plan.getActive(),
                plan.getCreatedAt(),
                plan.getUpdatedAt());
    }
}
