package com.invo.coopr8.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.invo.coopr8.model.PaymentProviderName;
import com.invo.coopr8.model.PaymentStatus;
import com.invo.coopr8.model.PaymentTransaction;

/**
 * COOPR8's own record of the payments it started.
 *
 * <p>Three methods, and they do different jobs on purpose.
 *
 * <p>{@link #findByProviderReference} is the platform's <strong>tenant resolution</strong> step for
 * an unauthenticated provider callback, which is why it is the one query here that does not filter
 * by organization -- it is what <em>determines</em> the organization. It is the same shape as
 * {@code TenantResolver.activeOrganizationBySlug}: a globally unique key, guaranteed unique by
 * {@code uk_payment_transaction_provider_reference}, resolving to exactly one cooperative. Nothing
 * outside the provider-callback path may use it, and its absence is a refusal rather than a fallback.
 *
 * <p>{@link #markSucceededWithinOrganization} is the platform's <strong>idempotency</strong> step,
 * and it is scoped by organization as well as by id so that the claim can only ever be made against
 * the cooperative the reference resolved to.
 *
 * <p>{@link #findOwnPaymentWithinOrganization} is the ordinary, fully scoped read, for an
 * authenticated member asking about a payment they started.
 */
@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    /**
     * The payment a provider reference names, across all cooperatives.
     *
     * <p><strong>This is tenant resolution, not a tenant-scoped read.</strong> An inbound provider
     * callback carries a reference and nothing else COOPR8 can trust; this row is what turns that
     * reference into a cooperative and a member. The reference is unique per provider by database
     * constraint, so it resolves to one payment or to none -- and none means "ignore", never "guess".
     */
    @Query("""
            SELECT t FROM PaymentTransaction t
            WHERE t.provider = :provider
              AND t.providerReference = :providerReference
            """)
    Optional<PaymentTransaction> findByProviderReference(
            @Param("provider") PaymentProviderName provider,
            @Param("providerReference") String providerReference);

    /**
     * Claims a pending payment for crediting, and reports whether this caller is the one that got it.
     *
     * <p>Returns {@code 1} for the caller that claimed the payment and {@code 0} for every other --
     * a duplicate provider event, a concurrent one, a retry. It is a compare-and-set rather than a
     * read followed by a write, because a read followed by a write has a window between the two in
     * which a second caller reads the same {@code PENDING} row and credits the member twice.
     * PostgreSQL serialises the two updates on the row lock, so the second sees {@code SUCCEEDED}
     * and matches nothing.
     *
     * <p>The caller must run this in the same transaction as the credit it guards. That is what makes
     * the pair atomic: a credit that fails rolls the claim back, leaving the payment claimable by the
     * provider's next retry rather than marked done with no money moved.
     */
    @Modifying
    @Query("""
            UPDATE PaymentTransaction t
               SET t.status = :succeeded,
                   t.processedAt = :processedAt
             WHERE t.id = :id
               AND t.organization.id = :organizationId
               AND t.status = :pending
            """)
    int markSucceededWithinOrganization(
            @Param("id") Long id,
            @Param("organizationId") Long organizationId,
            @Param("pending") PaymentStatus pending,
            @Param("succeeded") PaymentStatus succeeded,
            @Param("processedAt") LocalDateTime processedAt);

    /**
     * A payment the calling member started, within the calling member's own cooperative.
     *
     * <p>Both scopes are present because both are needed. Without the organization, a member of one
     * cooperative could ask about another cooperative's payment; without the user, any member could
     * ask about a fellow member's. The caller turns an empty result into a {@code 404}, so a
     * reference belonging to someone else is indistinguishable from one that does not exist.
     */
    @Query("""
            SELECT t FROM PaymentTransaction t
            WHERE t.providerReference = :providerReference
              AND t.organization.id = :organizationId
              AND t.user.id = :userId
            """)
    Optional<PaymentTransaction> findOwnPaymentWithinOrganization(
            @Param("providerReference") String providerReference,
            @Param("organizationId") Long organizationId,
            @Param("userId") Long userId);
}
