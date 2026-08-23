package com.invo.coopr8.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.invo.coopr8.model.OrganizationSharesConfig;

/**
 * One cooperative's share rules. Tenant-owned; see {@link UserRepository} for why nothing here
 * is global.
 *
 * <p>Scoped by {@code organizationId} rather than by primary key, because a primary-key load
 * bypasses {@code @Filter} and a share price is exactly the kind of figure that must not cross
 * a tenant boundary.
 */
public interface OrganizationSharesConfigRepository extends JpaRepository<OrganizationSharesConfig, Long> {

    Optional<OrganizationSharesConfig> findByOrganizationId(Long organizationId);

    boolean existsByOrganizationId(Long organizationId);
}
