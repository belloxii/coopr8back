package com.invo.coopr8.service;

import com.invo.coopr8.model.Organization;

/**
 * Evaluates feature entitlements for cooperatives on the COOPR8 platform.
 *
 * <p>Effective entitlement resolution follows the rule:
 * {@code organization override (if non-null) -> plan flag -> false}.
 *
 * <p>Organization identity is always derived from the verified JWT via {@link com.invo.coopr8.security.CurrentAuth},
 * never trusted from client request parameters or headers.
 */
public interface EntitlementService {

    /**
     * Checks whether the currently authenticated tenant is entitled to AI form scanning.
     */
    boolean isAiScanningEntitled();

    /**
     * Checks whether the currently authenticated tenant is entitled to eCommerce.
     */
    boolean isEcommerceEntitled();

    /**
     * Evaluates effective AI form scanning entitlement for a given organization.
     */
    boolean isAiScanningEntitled(Organization organization);

    /**
     * Evaluates effective eCommerce entitlement for a given organization.
     */
    boolean isEcommerceEntitled(Organization organization);

    /**
     * Enforces that the current tenant has AI form scanning entitlement, throwing
     * {@link org.springframework.security.access.AccessDeniedException} if not.
     */
    void requireAiScanning();

    /**
     * Enforces that the current tenant has eCommerce entitlement, throwing
     * {@link org.springframework.security.access.AccessDeniedException} if not.
     */
    void requireEcommerce();
}
