package com.invo.coopr8.dto.platform;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.invo.coopr8.model.PlanPriceAudit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanPriceAuditResponse {

    private Long id;
    private Long planId;
    private BigDecimal oldPrice;
    private BigDecimal newPrice;
    private BigDecimal oldPerUserPrice;
    private BigDecimal newPerUserPrice;
    private Long changedBy;
    private LocalDateTime changedAt;

    public static PlanPriceAuditResponse from(PlanPriceAudit audit) {
        return PlanPriceAuditResponse.builder()
                .id(audit.getId())
                .planId(audit.getPlanId())
                .oldPrice(audit.getOldPrice())
                .newPrice(audit.getNewPrice())
                .oldPerUserPrice(audit.getOldPerUserPrice())
                .newPerUserPrice(audit.getNewPerUserPrice())
                .changedBy(audit.getChangedBy())
                .changedAt(audit.getChangedAt())
                .build();
    }
}
