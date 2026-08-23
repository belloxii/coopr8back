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
 * A named savings plan a cooperative offers. <strong>Many rows per organization.</strong>
 *
 * <p>Savings plans exist today as three bare numbers on each member record
 * ({@code User.savingPlan}, {@code specialSavingPlan}, {@code sharePlan}). A number on a
 * member row cannot say what the plan is called, what it costs, or whether the cooperative
 * still offers it.
 *
 * <p><strong>Those three numeric fields on {@link User} stay exactly as they are.</strong>
 * Nothing here reads or rewrites them. Rewriting member plan data is a production data
 * change, not a schema change, and is not this entity's business.
 *
 * <p><strong>Never hard-deleted</strong>, for the same reason as
 * {@link OrganizationLoanType}: a plan a member has contributed under cannot be removed
 * without orphaning that contribution's terms. "Delete" means {@link #active} = false.
 *
 * <p>{@link #frequency} is a label, not a schedule — see {@link SavingsFrequency}. Nothing
 * in COOPR8 enforces contribution timing.
 */
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "organization_savings_plan")
public class OrganizationSavingsPlan {

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

    /** Unique per cooperative ignoring case and surrounding space. */
    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", length = 512)
    private String description;

    /**
     * The plan's own contribution figure, and the reason the plan exists. Not nullable: a
     * plan without an amount is not a plan.
     */
    @Column(name = "plan_amount", nullable = false)
    private BigDecimal planAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 32)
    private SavingsFrequency frequency;

    /**
     * Optional floor on what a member may post against this plan. {@code null} means no
     * bound — distinct from {@link #planAmount}, which is what the plan asks for rather
     * than what a posting is allowed to be.
     */
    @Column(name = "min_amount")
    private BigDecimal minAmount;

    /** Optional ceiling on a posting. {@code null} means no bound. */
    @Column(name = "max_amount")
    private BigDecimal maxAmount;

    /** False removes the plan from the enrolment form and leaves history intact. */
    @Column(name = "active", nullable = false)
    private Boolean active;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
