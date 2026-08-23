package com.invo.coopr8.repository;

import java.util.List;
import java.util.Optional;

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

    Optional<Loan> findByIdAndOrganizationId(Long id, Long organizationId);

    List<Loan> findAllByOrganizationIdOrderByIdDesc(Long organizationId);

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
    @Query("SELECT l FROM Loan l WHERE l.organization.id = :organizationId "
            + "AND (l.guarantor1.id = :userId OR l.guarantor2.id = :userId) "
            + "ORDER BY l.id DESC")
    List<Loan> findGuarantorRequests(@Param("organizationId") Long organizationId,
            @Param("userId") Long userId);
}
