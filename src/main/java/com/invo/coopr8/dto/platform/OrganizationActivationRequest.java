package com.invo.coopr8.dto.platform;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationActivationRequest {

    /**
     * Optional custom subscription expiry date.
     * If omitted, defaults to 1 year from the activation timestamp.
     */
    private LocalDateTime subscriptionEndsAt;

    /**
     * Optional agreed price override upon activation.
     * If omitted, the plan's default price is preserved.
     */
    private BigDecimal agreedPrice;
}
