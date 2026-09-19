package com.invo.coopr8.dto.platform;

import java.math.BigDecimal;

import com.invo.coopr8.model.Plan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanResponse {

    private Long id;
    private String code;
    private String name;
    private BigDecimal price;
    private String currency;
    private String billingPeriod;
    private BigDecimal perUserPrice;
    private Integer includedUsers;
    private boolean aiScanningEnabled;
    private boolean ecommerceEnabled;
    private boolean active;
    private int sortOrder;

    public static PlanResponse from(Plan plan) {
        return PlanResponse.builder()
                .id(plan.getId())
                .code(plan.getCode())
                .name(plan.getName())
                .price(plan.getPrice())
                .currency(plan.getCurrency())
                .billingPeriod(plan.getBillingPeriod())
                .perUserPrice(plan.getPerUserPrice())
                .includedUsers(plan.getIncludedUsers())
                .aiScanningEnabled(plan.isAiScanningEnabled())
                .ecommerceEnabled(plan.isEcommerceEnabled())
                .active(plan.isActive())
                .sortOrder(plan.getSortOrder())
                .build();
    }
}

