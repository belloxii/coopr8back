package com.invo.coopr8.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import com.invo.coopr8.model.OTP;
import com.invo.coopr8.model.OtpPurpose;

import jakarta.persistence.LockModeType;

/**
 * One-time codes. Tenant-owned.
 *
 * <p>The previous {@code findByEmail(String)} is gone. It was the mechanism by which two
 * cooperatives sharing a member's email address could redeem each other's codes: the
 * lookup could not tell the two rows apart, and returned whichever the database offered
 * first. Every finder here requires the organization id, so a code can only ever be
 * redeemed inside the tenant that issued it.
 */
public interface OTPRepository extends JpaRepository<OTP, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OTP> findByOrganizationIdAndEmailIgnoreCaseAndPurpose(
            Long organizationId, String email, OtpPurpose purpose);

    /**
     * Removes any outstanding code for this (tenant, email, purpose) before a new one is
     * issued, so at most one code is live per flow and a superseded code stops working
     * immediately.
     */
    @Modifying
    @Query("DELETE FROM OTP o WHERE o.organization.id = :organizationId "
            + "AND LOWER(o.email) = LOWER(:email) AND o.purpose = :purpose")
    int deleteForRecipient(@Param("organizationId") Long organizationId,
            @Param("email") String email, @Param("purpose") OtpPurpose purpose);

    /** Housekeeping: drop codes that can no longer be redeemed. */
    @Modifying
    @Query("DELETE FROM OTP o WHERE o.expiredAt < :cutoff")
    int deleteExpired(@Param("cutoff") LocalDateTime cutoff);
}
