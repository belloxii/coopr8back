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
 * One cooperative's loan rules. <strong>Exactly one row per organization</strong>, enforced
 * by {@code uk_organization_loan_config_organization} in the V3 migration — two rows would
 * be two interest rates, and which one applied would depend on row order.
 *
 * <p>These values replace rules currently compiled into {@code LoanServiceImpl}: no
 * interest, exactly two guarantors, and no amount or tenure bounds at all.
 *
 * <p><strong>A change here affects loans approved after it, and nothing else.</strong>
 * Existing approved loans are never recalculated: an approved loan carries its own applied
 * terms, so changing today's configuration cannot change the economics of a loan a member
 * is already repaying. There is deliberately no {@code valid_from} and no version column,
 * because there is no future-dated configuration.
 *
 * <p><strong>{@code null} means "no bound", not zero.</strong> A null
 * {@code maxLoanAmount} is a cooperative that has not set a ceiling; a zero ceiling would
 * forbid all borrowing. None of the four bound fields is defaulted, and code reading them
 * must treat null as "unbounded" rather than coalescing it away.
 *
 * <p>{@code interestRate} is an <strong>annual</strong> percentage — see
 * {@link InterestMethod} for why the basis is a platform constant rather than a column.
 * It needs an explicit precision: Hibernate 6 maps {@code BigDecimal} to
 * {@code numeric(38,2)} by default, which would neither match {@code numeric(6,3)} in the
 * migration nor hold three decimal places of a rate.
 *
 * <p>Column names are declared explicitly so the Flyway DDL matches exactly and Hibernate
 * {@code validate} passes regardless of the physical naming strategy.
 */
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "organization_loan_config")
public class OrganizationLoanConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Tenant owner. Isolation is enforced by scoped repository queries against this column;
     * the {@code @Filter} on the class is a secondary net -- see {@link TenantFilter}.
     *
     * <p>{@code @JsonIgnore} is not decoration: without it, serializing this entity walks
     * into {@link Organization} and returns another cooperative's record shape to whoever
     * asked for a rate. Configuration responses should be DTOs in any case.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    @JsonIgnore
    private Organization organization;

    @Enumerated(EnumType.STRING)
    @Column(name = "interest_method", nullable = false, length = 32)
    private InterestMethod interestMethod;

    /** Annual percentage. {@code 12.000} means 12% per year, not per month and not per loan. */
    @Column(name = "interest_rate", nullable = false, precision = 6, scale = 3)
    private BigDecimal interestRate;

    /** Smallest loan this cooperative will grant. {@code null} means no floor. */
    @Column(name = "min_loan_amount")
    private BigDecimal minLoanAmount;

    /** Largest loan this cooperative will grant. {@code null} means no ceiling. */
    @Column(name = "max_loan_amount")
    private BigDecimal maxLoanAmount;

    /** Shortest tenure in months. {@code null} means no floor. */
    @Column(name = "min_tenure_months")
    private Integer minTenureMonths;

    /** Longest tenure in months. {@code null} means no ceiling. */
    @Column(name = "max_tenure_months")
    private Integer maxTenureMonths;

    /**
     * How many guarantors a loan application requires, constrained to {@code 0..2}.
     *
     * <p>The cap is structural, not conservative: {@link Loan} has exactly two guarantor
     * columns and two guarantor status columns. Supporting three would need a
     * {@code loan_guarantor} child table and rework of the guarantor approval flow, so a
     * setting that accepted 3 and silently used 2 would be a lie told by the schema.
     */
    @Column(name = "required_guarantors", nullable = false)
    private Integer requiredGuarantors;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
