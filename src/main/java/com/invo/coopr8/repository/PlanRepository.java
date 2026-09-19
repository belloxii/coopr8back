package com.invo.coopr8.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.invo.coopr8.model.Plan;

/**
 * Global repository for commercial subscription plans.
 * Platform-global; see {@link TenantIsolationArchitectureTest} for exclusion.
 */
public interface PlanRepository extends JpaRepository<Plan, Long> {

    Optional<Plan> findByCode(String code);

    Optional<Plan> findByCodeIgnoreCase(String code);

    List<Plan> findAllByActiveTrueOrderBySortOrderAsc();

    List<Plan> findAllByOrderBySortOrderAsc();
}

