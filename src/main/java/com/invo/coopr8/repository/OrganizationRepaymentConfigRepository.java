package com.invo.coopr8.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.invo.coopr8.model.OrganizationRepaymentConfig;

/**
 * One cooperative's repayment rules. Tenant-owned; see {@link UserRepository} for why nothing
 * here is global.
 *
 * <p>Scoped by {@code organizationId} rather than by primary key, because a primary-key load
 * bypasses {@code @Filter}.
 */
public interface OrganizationRepaymentConfigRepository
        extends JpaRepository<OrganizationRepaymentConfig, Long> {

    Optional<OrganizationRepaymentConfig> findByOrganizationId(Long organizationId);

    boolean existsByOrganizationId(Long organizationId);
}
