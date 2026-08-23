package com.invo.coopr8.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.invo.coopr8.tenant.TenantFilter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One cooperative's repayment rules. <strong>Exactly one row per organization</strong>,
 * enforced by {@code uk_organization_repayment_config_organization} in the V6 migration.
 *
 * <p>Exists to fix a real defect. {@code RepayServiceImpl} requires a repayment to be an
 * exact multiple of the scheduled instalment, which makes some loans unpayable: ₦100,000
 * over 3 months gives an instalment of ₦33,333.33, three of which are ₦99,999.99, so the
 * final kobo can never be paid and the loan never closes.
 *
 * <p><strong>Penalties are deliberately absent from this entity.</strong> They are deferred
 * to a future phase, and a penalty field that no code charges is worse than no field: an
 * administrator who sees "late penalty 5%" in a settings screen will reasonably believe late
 * members are being charged 5%, and nothing in COOPR8 would charge it. There is no
 * {@code penaltyRate}, no {@code gracePeriodDays} and no {@code penaltyMethod} here, and
 * {@code OrganizationConfigClassCTest} asserts that no such column exists.
 */
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "organization_repayment_config")
public class OrganizationRepaymentConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Tenant owner. Isolation is enforced by scoped repository queries against this column;
     * the {@code @Filter} on the class is a secondary net -- see {@link TenantFilter}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    @JsonIgnore
    private Organization organization;

    /**
     * Whether a member may pay an amount that is not a whole multiple of the instalment.
     * False is today's behaviour, and is what every existing organization is seeded with.
     */
    @Column(name = "allow_partial_repayment", nullable = false)
    private Boolean allowPartialRepayment;

    /** Whether a member may pay more than the outstanding balance. False is today's behaviour. */
    @Column(name = "allow_overpayment", nullable = false)
    private Boolean allowOverpayment;

    /**
     * The residual this cooperative will treat as settled, in naira. {@code 0.00} means "to
     * the kobo", which is today's behaviour.
     *
     * <p>Capped at {@code 1.00} by {@code ck_organization_repayment_config_tolerance}: a
     * larger tolerance is not rounding, it is forgiving debt, and forgiving debt is a
     * deliberate transaction with an audit trail rather than a settings value.
     */
    @Column(name = "settlement_tolerance", nullable = false)
    private BigDecimal settlementTolerance;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
