package com.invo.coopr8.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.invo.coopr8.model.OrganizationSavingsPlan;

/**
 * The savings plans one cooperative offers. Tenant-owned; see {@link UserRepository} for why
 * nothing here is global.
 *
 * <p>Every method is scoped by {@code organizationId}, for the same reason as
 * {@link OrganizationLoanTypeRepository}: a primary-key load bypasses {@code @Filter}.
 *
 * <p>No delete method, for the same reason: a plan a member has contributed under is retired
 * with {@code active = false}, never removed.
 */
public interface OrganizationSavingsPlanRepository extends JpaRepository<OrganizationSavingsPlan, Long> {

    Optional<OrganizationSavingsPlan> findByIdAndOrganizationId(Long id, Long organizationId);

    /** Every plan, active or retired — the administrator's own view. */
    List<OrganizationSavingsPlan> findAllByOrganizationIdOrderByNameAsc(Long organizationId);

    /** Only what a member may currently enrol in. */
    List<OrganizationSavingsPlan> findAllByOrganizationIdAndActiveTrueOrderByNameAsc(Long organizationId);

    /** Case-insensitive, matching {@code ux_organization_savings_plan_org_name}. */
    Optional<OrganizationSavingsPlan> findByOrganizationIdAndNameIgnoreCase(Long organizationId, String name);
}
