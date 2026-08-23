package com.invo.coopr8.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.invo.coopr8.model.OrganizationLoanTypeExclusion;

/**
 * The pairs of loan types a cooperative's members may not hold at once.
 *
 * <p><strong>Every read here is symmetric, and that is the whole point.</strong>
 * {@code organization_loan_type_exclusion} stores one row per unordered pair, normalized so that the
 * smaller loan-type id is always in {@code loan_type_id} -- see
 * {@link OrganizationLoanTypeExclusion} for why two rows would be worse. A read that only looked at
 * {@code loan_type_id} would therefore find "Real excludes Material" but not "Material excludes
 * Real", and the rule would silently apply in one direction. So the two lookups below test both
 * columns, and the constraint tests exercise them from both directions.
 *
 * <p>The two symmetric methods are {@code @Query} rather than derived names because Spring Data
 * cannot express {@code AND (a OR b)}: {@code ...AndLoanTypeIdOrExcludedLoanTypeId} parses as
 * {@code (organization AND loanType) OR excluded}, which drops the tenant predicate from the second
 * disjunct. That is not a style choice -- the derived form would read rows from every cooperative on
 * the platform. Both queries name the organization explicitly.
 *
 * <p>There is deliberately no {@code delete*} method. Unlike a loan type, an exclusion genuinely is
 * removed when a cooperative changes its rule -- no historical loan points at one -- but removal must
 * go through {@link #findByIdAndOrganizationId}, whose result is already proven to belong to the
 * caller's tenant, and then {@code delete(entity)}. A {@code deleteById} would take an id from a
 * request body and delete another cooperative's rule.
 */
public interface OrganizationLoanTypeExclusionRepository
        extends JpaRepository<OrganizationLoanTypeExclusion, Long> {

    /** The tenant-scoped single-row read an administrator's edit or removal must start from. */
    Optional<OrganizationLoanTypeExclusion> findByIdAndOrganizationId(Long id, Long organizationId);

    /** Every rule this cooperative has configured, in a stable order for an admin listing. */
    List<OrganizationLoanTypeExclusion>
            findAllByOrganizationIdOrderByLoanTypeIdAscExcludedLoanTypeIdAsc(Long organizationId);

    /**
     * Every rule that concerns one loan type, whichever column it sits in.
     *
     * <p>This is the read a loan application needs: "the member wants Real -- what is Real
     * incompatible with?" {@link OrganizationLoanTypeExclusion#counterpartOf(Long)} turns each row
     * into the answer.
     */
    @Query("SELECT e FROM OrganizationLoanTypeExclusion e "
            + "WHERE e.organization.id = :organizationId "
            + "AND (e.loanTypeId = :loanTypeId OR e.excludedLoanTypeId = :loanTypeId) "
            + "ORDER BY e.loanTypeId ASC, e.excludedLoanTypeId ASC")
    List<OrganizationLoanTypeExclusion> findAllInvolvingLoanType(
            @Param("organizationId") Long organizationId,
            @Param("loanTypeId") Long loanTypeId);

    /**
     * Whether these two loan types are mutually exclusive, in either order of argument.
     *
     * <p>{@code existsBetween(org, real, material)} and {@code existsBetween(org, material, real)}
     * resolve to the same row. The caller does not have to know which id is the smaller one, which
     * is exactly the knowledge normalization must not leak out of this class.
     */
    @Query("SELECT count(e) > 0 FROM OrganizationLoanTypeExclusion e "
            + "WHERE e.organization.id = :organizationId "
            + "AND ((e.loanTypeId = :firstLoanTypeId "
            + "      AND e.excludedLoanTypeId = :secondLoanTypeId) "
            + "  OR (e.loanTypeId = :secondLoanTypeId "
            + "      AND e.excludedLoanTypeId = :firstLoanTypeId))")
    boolean existsBetween(@Param("organizationId") Long organizationId,
            @Param("firstLoanTypeId") Long firstLoanTypeId,
            @Param("secondLoanTypeId") Long secondLoanTypeId);
}
