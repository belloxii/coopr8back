package com.invo.coopr8.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.invo.coopr8.model.OrganizationLoanConfig;

/**
 * One cooperative's loan rules. Tenant-owned; see {@link UserRepository} for why nothing here
 * is global.
 *
 * <p>Every method is scoped by {@code organizationId}, including the lookup that could have
 * been {@code findById}. That is not redundancy: Hibernate does NOT apply {@code @Filter} to a
 * load by primary key, so a bare {@code findById} on a configuration row is an unfiltered
 * cross-tenant read of another cooperative's interest rate. The scoped query is the isolation
 * boundary; the filter is the net behind it.
 *
 * <p>{@code findByOrganizationId} returns an {@link Optional} of at most one row because
 * {@code uk_organization_loan_config_organization} makes a second row impossible.
 */
public interface OrganizationLoanConfigRepository extends JpaRepository<OrganizationLoanConfig, Long> {

    Optional<OrganizationLoanConfig> findByOrganizationId(Long organizationId);

    /**
     * Whether this cooperative has been configured at all.
     *
     * <p>Distinguishes "no configuration row" — a seeding failure, and a reason to refuse
     * rather than to guess — from a configured row whose optional bounds happen to be null.
     */
    boolean existsByOrganizationId(Long organizationId);
}
