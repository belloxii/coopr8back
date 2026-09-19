package com.invo.coopr8.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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
 * A commercial subscription plan on the COOPR8 platform.
 *
 * <p>This is a PLATFORM-GLOBAL entity (owned by Invo, not by any single tenant).
 * Deliberately has no organization_id.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "plans")
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Builder.Default
    @Column(name = "currency", nullable = false, length = 8)
    private String currency = "NGN";

    @Builder.Default
    @Column(name = "billing_period", nullable = false, length = 32)
    private String billingPeriod = "ANNUAL";

    @Column(name = "per_user_price", precision = 12, scale = 2)
    private BigDecimal perUserPrice;

    @Column(name = "included_users")
    private Integer includedUsers;

    @Builder.Default
    @Column(name = "ai_scanning_enabled", nullable = false)
    private boolean aiScanningEnabled = false;

    @Builder.Default
    @Column(name = "ecommerce_enabled", nullable = false)
    private boolean ecommerceEnabled = false;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Builder.Default
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}

