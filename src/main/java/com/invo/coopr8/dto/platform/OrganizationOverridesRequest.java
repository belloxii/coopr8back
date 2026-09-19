package com.invo.coopr8.dto.platform;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationOverridesRequest {

    /**
     * Override flag for AI form scanning entitlement.
     * Set to true/false to override the plan, or null to inherit from plan.
     */
    private Boolean aiScanningOverride;

    /**
     * Override flag for eCommerce entitlement.
     * Set to true/false to override the plan, or null to inherit from plan.
     */
    private Boolean ecommerceOverride;
}
