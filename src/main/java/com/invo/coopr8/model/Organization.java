package com.invo.coopr8.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.hibernate.annotations.UpdateTimestamp;

import com.invo.coopr8.tenant.TenantFilter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * A tenant on the COOPR8 platform: one cooperative society, with its own name,
 * logo, colours and member-ledger prefix. Every tenant-owned row points at one of
 * these, and all tenant-facing branding (UI, emails, documents) is read from here
 * rather than hardcoded.
 *
 * This is a PLATFORM-GLOBAL entity (owned by Invo, not by any single tenant) --
 * it deliberately has no organization_id of its own.
 *
 * No organization is seeded by migration; each is created deliberately when a
 * cooperative is onboarded.
 *
 * Column names are declared explicitly so the Flyway DDL matches exactly and
 * Hibernate `validate` passes regardless of the physical naming strategy.
 *
 * <p>The tenant root also declares the shared tenant filter -- a {@code @FilterDef} is a
 * global registration, so one declaration here serves every {@code @Filter} on the
 * tenant-owned entities. This entity is deliberately NOT filtered itself: it has no
 * organization_id, and pre-authentication tenant discovery (login, public branding) has to
 * be able to read it. See {@link com.invo.coopr8.tenant.TenantFilter} for what the filter
 * does and does not protect, and why it is never auto-enabled.
 */
@FilterDef(
        name = TenantFilter.NAME,
        defaultCondition = TenantFilter.CONDITION,
        parameters = @ParamDef(name = TenantFilter.PARAMETER, type = Long.class))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "organizations")
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "legal_name")
    private String legalName;

    @Column(name = "slug", nullable = false, unique = true)
    private String slug;

    @Column(name = "email")
    private String email;

    @Column(name = "phone")
    private String phone;

    @Column(name = "address")
    private String address;

    @Column(name = "website")
    private String website;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "primary_color")
    private String primaryColor;

    @Column(name = "secondary_color")
    private String secondaryColor;

    /**
     * Prefix for this tenant's member ledger IDs, e.g. "ABC" produces ABC0001.
     * Set per organization at onboarding; there is no platform-wide default.
     */
    @Column(name = "ledger_prefix", nullable = false)
    private String ledgerPrefix;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OrganizationStatus status;

    @Column(name = "plan_code")
    private String planCode;

    @Column(name = "agreed_price")
    private BigDecimal agreedPrice;

    @Column(name = "billing_period_snapshot")
    private String billingPeriodSnapshot;

    @Column(name = "per_user_price_snapshot")
    private BigDecimal perUserPriceSnapshot;

    @Column(name = "included_users_snapshot")
    private Integer includedUsersSnapshot;

    @Column(name = "ai_scanning_override")
    private Boolean aiScanningOverride;

    @Column(name = "ecommerce_override")
    private Boolean ecommerceOverride;

    @Column(name = "subscription_starts_at")
    private LocalDateTime subscriptionStartsAt;

    @Column(name = "subscription_ends_at")
    private LocalDateTime subscriptionEndsAt;

    @Column(name = "subdomain", unique = true)
    private String subdomain;

    @Column(name = "custom_domain", unique = true)
    private String customDomain;

    @Builder.Default
    @Column(name = "custom_domain_verified", nullable = false)
    private boolean customDomainVerified = false;

    @Column(name = "custom_domain_verification_token")
    private String customDomainVerificationToken;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
