package com.invo.coopr8.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Append-only audit record of plan price changes.
 *
 * <p>Platform-global entity; deliberately has no organization_id.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "plan_price_audit")
public class PlanPriceAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "old_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal oldPrice;

    @Column(name = "new_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal newPrice;

    @Column(name = "old_per_user_price", precision = 12, scale = 2)
    private BigDecimal oldPerUserPrice;

    @Column(name = "new_per_user_price", precision = 12, scale = 2)
    private BigDecimal newPerUserPrice;

    @Column(name = "changed_by", nullable = false)
    private Long changedBy;

    @CreationTimestamp
    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;
}

