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
 * One cooperative's share rules. <strong>Exactly one row per organization</strong>, enforced
 * by {@code uk_organization_shares_config_organization} in the V5 migration — two rows would
 * be two share prices.
 *
 * <p>Replaces rules currently hardcoded in {@code SharesServiceImpl}: approval always
 * required, purchase {@code > 0}, withdrawal {@code > 0} and {@code <= balance}.
 *
 * <p><strong>{@link #sharePrice} introduces a unit concept the platform does not currently
 * have.</strong> Today {@code Shares} holds a naira amount and nothing else, so "how many
 * shares does this member hold" is unanswerable. A price of {@code 1.00} makes units equal
 * naira, which is exactly today's arithmetic, and that is what every existing organization
 * is seeded with — so shipping this reprices nobody's holding.
 *
 * <p>A cooperative that later sets a real price affects <strong>postings made after the
 * change</strong>. Existing {@code shares} rows are never repriced.
 */
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "organization_shares_config")
public class OrganizationSharesConfig {

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
     * Naira price of one share. Strictly positive: a zero price makes units
     * {@code amount / 0}, and a negative one is not a share.
     */
    @Column(name = "share_price", nullable = false)
    private BigDecimal sharePrice;

    /** {@code null} means no floor on a purchase. */
    @Column(name = "min_purchase_amount")
    private BigDecimal minPurchaseAmount;

    /** {@code null} means no ceiling on a purchase. */
    @Column(name = "max_purchase_amount")
    private BigDecimal maxPurchaseAmount;

    /** Whether a share purchase waits for an administrator. True is today's behaviour. */
    @Column(name = "approval_required", nullable = false)
    private Boolean approvalRequired;

    /** Whether members may withdraw shares at all. True is today's behaviour. */
    @Column(name = "withdrawal_allowed", nullable = false)
    private Boolean withdrawalAllowed;

    /** {@code null} means no floor on a withdrawal. */
    @Column(name = "min_withdrawal_amount")
    private BigDecimal minWithdrawalAmount;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
