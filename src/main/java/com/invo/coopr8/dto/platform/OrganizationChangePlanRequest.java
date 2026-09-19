package com.invo.coopr8.dto.platform;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationChangePlanRequest {

    @NotBlank(message = "Plan code is required")
    private String planCode;

    /**
     * Optional updated subscription end date.
     * If omitted, existing subscription dates are kept unchanged.
     */
    private LocalDateTime subscriptionEndsAt;

    /**
     * Optional agreed price override.
     * If omitted, the new plan's default price is snapshotted.
     */
    private BigDecimal agreedPrice;
}
