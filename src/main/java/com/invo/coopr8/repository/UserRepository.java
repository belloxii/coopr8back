package com.invo.coopr8.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.invo.coopr8.model.User;

/**
 * Members and admins. Tenant-owned, so every finder here takes the organization id.
 *
 * <p><strong>There is deliberately no global finder.</strong> The pre-Phase-2 interface had
 * {@code findByLedgerID}, {@code findByEmail}, {@code findByPhone} and {@code findFirstByPsn}
 * with no organization argument, and each one was a way to read (or authenticate as) a member
 * of another cooperative. They are gone rather than deprecated: an unscoped finder that still
 * compiles is an unscoped finder that will be called.
 *
 * <p>{@code JpaRepository} still contributes {@code findAll()}, {@code findById()} and friends.
 * Those are not usable for tenant-owned reads in request handling, and
 * {@code TenantIsolationArchitectureTest} fails the build if application code calls them on
 * this repository.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    // ---------------------------------------------------------------- authentication

    /**
     * The single lookup used to authenticate a login.
     *
     * <p>The organization is resolved first (from the tenant's own URL, or from the ledger
     * prefix for legacy IDs) and passed in here. There is no way to search for a ledger ID
     * across organizations, which is what makes two cooperatives able to both issue an
     * {@code ABC0001} without one member's number ever matching the other's account.
     */
    Optional<User> findByLedgerIDAndOrganizationId(String ledgerID, Long organizationId);

    /**
     * Used only by the tenant-scoped password-reset flow, which has already established which
     * organization the request is for.
     */
    Optional<User> findByEmailIgnoreCaseAndOrganizationId(String email, Long organizationId);

    // ------------------------------------------------------------- scoped read access

    Optional<User> findByIdAndOrganizationId(Long id, Long organizationId);

    List<User> findAllByOrganizationIdOrderByIdAsc(Long organizationId);

    List<User> findByStatusIgnoreCaseAndOrganizationId(String status, Long organizationId);

    /**
     * Members in any of the given statuses, within one organization.
     *
     * <p>Spelled as a query because {@code IN} and {@code IgnoreCase} do not combine in a
     * derived name, and status values in existing data vary in case.
     */
    @Query("SELECT u FROM User u WHERE u.organization.id = :organizationId "
            + "AND UPPER(u.status) IN :statuses ORDER BY u.id ASC")
    List<User> findByOrganizationIdAndStatusIn(@Param("organizationId") Long organizationId,
            @Param("statuses") Collection<String> statuses);

    Optional<User> findByPhoneAndOrganizationId(String phone, Long organizationId);

    Optional<User> findFirstByPsnAndOrganizationId(String psn, Long organizationId);

    // ------------------------------------------------------------ scoped existence

    boolean existsByEmailIgnoreCaseAndOrganizationId(String email, Long organizationId);

    boolean existsByPhoneAndOrganizationId(String phone, Long organizationId);

    /**
     * Duplicate-email check that ignores one row -- the member being edited -- so saving a
     * profile without changing the email address is not reported as a collision with itself.
     */
    boolean existsByEmailIgnoreCaseAndOrganizationIdAndIdNot(String email, Long organizationId, Long id);

    /**
     * Same idea for phone numbers. Phone uniqueness matters beyond tidiness: guarantors are
     * nominated <em>by phone number</em> when applying for a loan, so two members of one
     * cooperative sharing a number would make that nomination ambiguous.
     */
    boolean existsByPhoneAndOrganizationIdAndIdNot(String phone, Long organizationId, Long id);

    // -------------------------------------------------------------------- aggregates

    /**
     * Highest member number issued <em>within one organization</em>.
     *
     * <p>Scoped per tenant so each organization numbers its members from 0001 and two
     * organizations can never contend for the same ledger sequence.
     */
    @Query("SELECT MAX(u.ledgerNumber) FROM User u WHERE u.organization.id = :organizationId")
    Integer findMaxLedgerNumberByOrganization(@Param("organizationId") Long organizationId);
}
