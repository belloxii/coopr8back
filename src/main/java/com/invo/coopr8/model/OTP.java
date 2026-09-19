package com.invo.coopr8.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;

import com.invo.coopr8.tenant.TenantFilter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A short-lived one-time code, scoped to one tenant and one purpose.
 *
 * <p><strong>Why the organization is on this row.</strong> Email is unique only
 * <em>within</em> an organization, so {@code (email, otp)} alone identifies a code
 * ambiguously as soon as two cooperatives share a member's email address. A code issued to
 * {@code ada@example.com} at cooperative A would otherwise verify {@code ada@example.com}
 * at cooperative B and reset the wrong account.
 *
 * <p>Lookup is always {@code (organizationId, email, purpose)} -- see
 * {@link com.invo.coopr8.repository.OTPRepository}. There are deliberately no finders
 * keyed on email alone.
 *
 * <p>The tenant {@code @Filter} applies here too, but note that the flows that matter most
 * -- signup and forgot-password -- are unauthenticated, so no tenant is bound and the filter
 * is off. The repository's {@code (organizationId, email, purpose)} scoping is what keeps a
 * code issued at one cooperative from redeeming an account at another.
 */
@Entity
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OTP {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String otp;

    /** Failed redemption attempts. Codes are discarded after a bounded number. */
    @Column(nullable = false)
    @Builder.Default
    private int failedAttempts = 0;

    private String email;

    /** Tenant this code belongs to. Never null: a code with no tenant cannot be verified safely. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    /** The single flow allowed to redeem this code. */
    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private OtpPurpose purpose;

    @CreationTimestamp
    private LocalDateTime createdAt;

    private LocalDateTime expiredAt;
}
