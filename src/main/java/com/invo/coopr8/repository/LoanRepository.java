package com.invo.coopr8.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.invo.coopr8.model.Loan;
import com.invo.coopr8.model.User;

/**
 * Loans. Tenant-owned: every finder is scoped by organization id, so a loan id guessed or
 * copied from another cooperative simply does not resolve.
 *
 * <p>The unscoped {@code findByUser_Id}, {@code findFirstByUserAndTypeAndStatusIn} and
 * {@code findByUserAndStatusIn} were removed rather than kept alongside the scoped versions.
 */
public interface LoanRepository extends JpaRepository<Loan, Long> {

    /*
     * Why four of the finders below carry an entity graph, and the other two deliberately do not.
     *
     * Loan.repays is a LAZY @OneToMany that Jackson is told to serialize (@JsonManagedReference),
     * and the frontend reads it: both loan lists sum item.repays, and AdminLoanDetails renders the
     * rows. It is part of these endpoints' contract, so it has to be *loaded* -- @JsonIgnore would
     * fix the exception by silently removing a field three screens depend on.
     *
     * Jackson runs after the transaction that loaded the loan has ended, so the collection must be
     * fetched by the query rather than on demand. Production masks the omission: Boot leaves
     * spring.jpa.open-in-view at true, which holds a session open for the whole request. The test
     * profile sets it to false, which is why CI reports "failed to lazily initialize a collection
     * of role: com.invo.coopr8.model.Loan.repays" and a developer running the app never sees it.
     *
     * EntityGraphType.LOAD, not the FETCH default, and this is load-bearing: under a *fetch* graph
     * JPA treats every attribute absent from the graph as LAZY, which would quietly demote the
     * EAGER user, guarantor1 and guarantor2 and move the same failure onto loan.user. LOAD means
     * "what you would normally load, plus repays".
     *
     * The graph sits on the four finders whose results are serialized rather than on the entity as
     * FetchType.EAGER, because findFirstByUserAndTypeAndStatusInAndOrganizationId and
     * findByUserAndStatusInAndOrganizationId only ask "does this member already hold such a loan"
     * and "which loans absorb this repayment". Neither has any use for the repayment history, and
     * an EAGER collection would fetch it for them anyway -- one extra SELECT per loan, forever.
     */

    @EntityGraph(attributePaths = "repays", type = EntityGraph.EntityGraphType.LOAD)
    Optional<Loan> findByIdAndOrganizationId(Long id, Long organizationId);

    @EntityGraph(attributePaths = "repays", type = EntityGraph.EntityGraphType.LOAD)
    List<Loan> findAllByOrganizationIdOrderByIdDesc(Long organizationId);

    @EntityGraph(attributePaths = "repays", type = EntityGraph.EntityGraphType.LOAD)
    List<Loan> findByUser_IdAndOrganizationId(Long userId, Long organizationId);

    Optional<Loan> findFirstByUserAndTypeAndStatusInAndOrganizationId(
            User user, String type, List<String> statuses, Long organizationId);

    List<Loan> findByUserAndStatusInAndOrganizationId(
            User user, List<String> statuses, Long organizationId);

    /**
     * Loans on which this member is recorded as a guarantor.
     *
     * <p>Written as a query rather than a derived name because the tenant scope has to apply
     * to the whole {@code OR}, not to one side of it. This previously read every loan on the
     * platform and filtered in memory.
     */
    @EntityGraph(attributePaths = "repays", type = EntityGraph.EntityGraphType.LOAD)
    @Query("SELECT l FROM Loan l WHERE l.organization.id = :organizationId "
            + "AND (l.guarantor1.id = :userId OR l.guarantor2.id = :userId) "
            + "ORDER BY l.id DESC")
    List<Loan> findGuarantorRequests(@Param("organizationId") Long organizationId,
            @Param("userId") Long userId);
}
