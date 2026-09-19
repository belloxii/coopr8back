package com.invo.coopr8.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.invo.coopr8.model.PlanPriceAudit;

/**
 * Append-only repository for plan price change audits.
 * Platform-global; see {@link TenantIsolationArchitectureTest} for exclusion.
 */
public interface PlanPriceAuditRepository extends JpaRepository<PlanPriceAudit, Long> {

    List<PlanPriceAudit> findAllByPlanIdOrderByChangedAtDesc(Long planId);
}

