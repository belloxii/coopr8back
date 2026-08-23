package com.invo.coopr8.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.invo.coopr8.model.OrganizationMembershipConfig;

/**
 * One cooperative's onboarding rules. Tenant-owned; see {@link UserRepository} for why nothing
 * here is global.
 *
 * <p>Scoped by {@code organizationId} rather than by primary key, because a primary-key load
 * bypasses {@code @Filter}.
 *
 * <p>Note for the onboarding read path: public signup has no JWT, so the organization comes
 * from the slug in the URL the member arrived at, resolved by
 * {@code OrganizationService.resolveRequestedOrganization} exactly as account creation already
 * does. The tenant is still never taken from a field in the request body.
 */
public interface OrganizationMembershipConfigRepository
        extends JpaRepository<OrganizationMembershipConfig, Long> {

    Optional<OrganizationMembershipConfig> findByOrganizationId(Long organizationId);

    boolean existsByOrganizationId(Long organizationId);
}
