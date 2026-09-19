package com.invo.coopr8.dto.platform;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.invo.coopr8.model.OrganizationStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformOrganizationResponse {

    private Long id;
    private String name;
    private String legalName;
    private String slug;
    private String email;
    private String phone;
    private String ledgerPrefix;
    private OrganizationStatus status;
    private String planCode;
    private String planName;
    private BigDecimal agreedPrice;
    private BigDecimal agreedPerUserPrice;
    private Integer includedUsers;
    private LocalDateTime subscriptionStartsAt;
    private LocalDateTime subscriptionEndsAt;
    private Long daysRemaining;
    private boolean complimentary;
    private Boolean aiScanningOverride;
    private Boolean ecommerceOverride;
    private boolean effectiveAiScanning;
    private boolean effectiveEcommerce;
    private long activeMemberCount;
    private LocalDateTime createdAt;
}
