package com.invo.coopr8.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.invo.coopr8.model.OrganizationLoanType;

/**
 * The loan products one cooperative offers. Tenant-owned; see {@link UserRepository} for why
 * nothing here is global.
 *
 * <p>Every method is scoped by {@code organizationId}, {@code findByIdAndOrganizationId}
 * included: Hibernate does not apply {@code @Filter} to a primary-key load, so an unscoped
 * {@code findById} would hand out another cooperative's product and its rate override.
 *
 * <p>There is deliberately no delete method. A product referenced by a historical loan cannot
 * be removed without orphaning that loan's terms, so deactivation is the only retirement
 * path — {@code active = false}, which {@code findAllByOrganizationIdAndActiveTrue...}
 * respects and the unfiltered listing does not.
 */
public interface OrganizationLoanTypeRepository extends JpaRepository<OrganizationLoanType, Long> {

    Optional<OrganizationLoanType> findByIdAndOrganizationId(Long id, Long organizationId);

    /** Every product, active or retired — the administrator's own view. */
    List<OrganizationLoanType> findAllByOrganizationIdOrderByNameAsc(Long organizationId);

    /** Only what a member may currently apply for. */
    List<OrganizationLoanType> findAllByOrganizationIdAndActiveTrueOrderByNameAsc(Long organizationId);

    /**
     * Resolves a product by the name a member submitted.
     *
     * <p>Case-insensitive because {@code ux_organization_loan_type_org_name} is, so "Soft" and
     * "soft" must resolve to the one row that exists rather than to none.
     *
     * <p>The index is the stricter of the two, though: since V11 its key also collapses interior
     * whitespace, so {@code "Soft  Loan"} cannot be stored alongside {@code "Soft Loan"} but will
     * not be found by this finder either. That gap is harmless while names are chosen from a list
     * the cooperative itself configured; a free-text lookup path would need to normalize the
     * argument the same way the index does.
     */
    Optional<OrganizationLoanType> findByOrganizationIdAndNameIgnoreCase(Long organizationId, String name);
}
