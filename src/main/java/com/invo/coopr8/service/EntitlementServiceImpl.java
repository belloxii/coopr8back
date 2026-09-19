package com.invo.coopr8.service;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.invo.coopr8.model.Organization;
import com.invo.coopr8.model.Plan;
import com.invo.coopr8.repository.OrganizationRepository;
import com.invo.coopr8.repository.PlanRepository;
import com.invo.coopr8.security.CurrentAuth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of {@link EntitlementService}.
 *
 * <p>Organization is read from the verified authentication token via {@link CurrentAuth},
 * never from client input.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EntitlementServiceImpl implements EntitlementService {

    private final OrganizationRepository organizationRepository;
    private final PlanRepository planRepository;

    @Override
    public boolean isAiScanningEntitled() {
        Long orgId = CurrentAuth.organizationId()
                .orElseThrow(() -> new AccessDeniedException("No authenticated tenant found in request context"));
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new AccessDeniedException("Organization not found"));
        return isAiScanningEntitled(org);
    }

    @Override
    public boolean isEcommerceEntitled() {
        Long orgId = CurrentAuth.organizationId()
                .orElseThrow(() -> new AccessDeniedException("No authenticated tenant found in request context"));
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new AccessDeniedException("Organization not found"));
        return isEcommerceEntitled(org);
    }

    @Override
    public boolean isAiScanningEntitled(Organization organization) {
        if (organization == null) {
            return false;
        }
        if (organization.getAiScanningOverride() != null) {
            return organization.getAiScanningOverride();
        }
        if (organization.getPlanCode() == null || organization.getPlanCode().isBlank()) {
            return false;
        }
        return planRepository.findByCodeIgnoreCase(organization.getPlanCode())
                .map(Plan::isAiScanningEnabled)
                .orElse(false);
    }

    @Override
    public boolean isEcommerceEntitled(Organization organization) {
        if (organization == null) {
            return false;
        }
        if (organization.getEcommerceOverride() != null) {
            return organization.getEcommerceOverride();
        }
        if (organization.getPlanCode() == null || organization.getPlanCode().isBlank()) {
            return false;
        }
        return planRepository.findByCodeIgnoreCase(organization.getPlanCode())
                .map(Plan::isEcommerceEnabled)
                .orElse(false);
    }

    @Override
    public void requireAiScanning() {
        if (!isAiScanningEntitled()) {
            log.warn("Access denied: organization {} attempted to access AI scanning without entitlement",
                    CurrentAuth.organizationId().orElse(null));
            throw new AccessDeniedException("This organization is not entitled to AI form scanning.");
        }
    }

    @Override
    public void requireEcommerce() {
        if (!isEcommerceEntitled()) {
            log.warn("Access denied: organization {} attempted to access eCommerce without entitlement",
                    CurrentAuth.organizationId().orElse(null));
            throw new AccessDeniedException("This organization is not entitled to eCommerce.");
        }
    }
}
