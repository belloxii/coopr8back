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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * A named loan product a cooperative offers. <strong>Many rows per organization.</strong>
 *
 * <p>Replaces the dead {@code LoanType} enum {@code {real, soft, material}}, which was
 * Citadel's product list compiled into the platform. {@code Loan.type} is already a
 * {@code String}, so historical values keep working untouched.
 *
 * <p><strong>Never hard-deleted.</strong> A type referenced by a historical loan cannot be
 * removed without orphaning that loan's terms, so "delete" in the admin UI means
 * {@link #active} = false. Deactivation removes the product from the application form; it
 * does not remove it from history.
 *
 * <p><strong>The override fields are nullable and mean "inherit from
 * {@link OrganizationLoanConfig}".</strong> {@code null} is the only honest encoding:
 * {@code 0.000} would be an explicit interest-free product, which is a different statement
 * about the world. Code resolving an effective rate must distinguish the two.
 *
 * <p>{@code uk_organization_loan_type_org_id} in V3 makes {@code (organization_id, id)}
 * referenceable, so a loan's snapshotted type can be a genuine composite foreign key and
 * the database — not application care — refuses a cross-tenant reference.
 */
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "organization_loan_type")
public class OrganizationLoanType {

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
     * The product name a member sees, and the value written to {@code Loan.type}. Unique
     * per cooperative ignoring case and surrounding space, because "Soft" and "soft" are
     * the same product on an application form.
     */
    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", length = 512)
    private String description;

    /** {@code null} inherits the organization's method. */
    @Enumerated(EnumType.STRING)
    @Column(name = "interest_method", length = 32)
    private InterestMethod interestMethod;

    /** Annual percentage. {@code null} inherits the organization's rate. */
    @Column(name = "interest_rate", precision = 6, scale = 3)
    private BigDecimal interestRate;

    /** {@code null} inherits the organization's floor, which may itself be unbounded. */
    @Column(name = "min_loan_amount")
    private BigDecimal minLoanAmount;

    /** {@code null} inherits the organization's ceiling, which may itself be unbounded. */
    @Column(name = "max_loan_amount")
    private BigDecimal maxLoanAmount;

    @Column(name = "min_tenure_months")
    private Integer minTenureMonths;

    @Column(name = "max_tenure_months")
    private Integer maxTenureMonths;

    /**
     * How many of this product a member may have running at once.
     *
     * <p>{@code 1} reproduces the "one active loan per type" rule currently hardcoded in
     * {@code LoanServiceImpl.applyLoan}.
     */
    @Column(name = "max_active_loans", nullable = false)
    private Integer maxActiveLoans;

    /**
     * Whether the product is currently offered. False removes it from the application form
     * and leaves every historical loan that used it intact.
     */
    @Column(name = "active", nullable = false)
    private Boolean active;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
