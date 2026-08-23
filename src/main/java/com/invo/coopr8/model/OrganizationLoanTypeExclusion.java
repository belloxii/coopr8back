package com.invo.coopr8.model;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A pair of one cooperative's loan types that a member may not hold at the same time.
 *
 * <p>Replaces the two string literals in {@code LoanServiceImpl} that refuse a "real" loan to a
 * holder of a "material" one. {@link OrganizationLoanType} could not express this: a rule about two
 * products has no single product to live on.
 *
 * <p><strong>The pair is unordered and stored once.</strong> "Real excludes Material" and "Material
 * excludes Real" are one rule. Two rows would let an administrator delete one half and leave an
 * exclusion that applies in one direction only -- a member blocked from borrowing Real while holding
 * Material but allowed the reverse, which reads as an intermittent bug and is nearly impossible to
 * reproduce. So {@link #between} normalizes: the smaller id always lands in {@code loanTypeId}, the
 * larger always in {@code excludedLoanTypeId}, and
 * {@code ck_organization_loan_type_exclusion_normalized} in V10 makes that a database guarantee
 * rather than a convention this class is trusted to keep. Because the pair is normalized, reads must
 * be symmetric -- see {@code OrganizationLoanTypeExclusionRepository}.
 *
 * <p><strong>Why this entity's Lombok differs from its six siblings.</strong> There is no
 * {@code @Builder}, no {@code @AllArgsConstructor} and no {@code @Setter}, on purpose. Any of the
 * three would let a caller construct an unnormalized pair that the database then refuses at flush
 * with a CHECK violation naming a constraint the caller never heard of. {@link #between} is the only
 * way to make one, so normalization cannot be forgotten. Immutability follows the same reasoning:
 * "editing" an exclusion means retiring one rule and recording another, because silently swapping
 * which two products a rule covers would leave its audit record describing a rule that no longer
 * exists.
 *
 * <p>The two loan types are held as raw ids rather than as {@code @ManyToOne} associations because
 * the foreign keys are COMPOSITE -- {@code (organization_id, loan_type_id)} against
 * {@code organization_loan_type (organization_id, id)} -- which is what makes a cross-tenant
 * exclusion impossible at the database level. Mapping them as associations would require
 * {@code organization_id} to be mapped three times over. {@code Loan} already stores its type
 * without an association, so this is the existing shape rather than a new one.
 *
 * <p>Nothing reads this table yet. Stage 1 is schema, entity and repository only.
 */
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "organization_loan_type_exclusion")
public class OrganizationLoanTypeExclusion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Tenant owner. Isolation is enforced by scoped repository queries against this column;
     * the {@code @Filter} on the class is a secondary net -- see {@link TenantFilter}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false)
    @JsonIgnore
    private Organization organization;

    /** The lower of the two ids. Enforced by the V10 CHECK, not merely by {@link #between}. */
    @Column(name = "loan_type_id", nullable = false, updatable = false)
    private Long loanTypeId;

    /** The higher of the two ids. */
    @Column(name = "excluded_loan_type_id", nullable = false, updatable = false)
    private Long excludedLoanTypeId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * Creates the one row that represents an unordered pair, in either order of argument.
     *
     * @throws IllegalArgumentException if the two ids are equal. A type that excluded itself would
     *         forbid a second loan of that type, which is what
     *         {@link OrganizationLoanType#getMaxActiveLoans()} already governs -- two mechanisms for
     *         one rule, free to disagree later. The database refuses it too; this is the earlier and
     *         more legible of the two refusals.
     */
    public static OrganizationLoanTypeExclusion between(Organization organization,
            Long firstLoanTypeId, Long secondLoanTypeId) {
        if (organization == null || firstLoanTypeId == null || secondLoanTypeId == null) {
            throw new IllegalArgumentException(
                    "an exclusion needs a cooperative and two loan types");
        }
        if (firstLoanTypeId.equals(secondLoanTypeId)) {
            throw new IllegalArgumentException(
                    "a loan type cannot exclude itself: " + firstLoanTypeId
                            + " -- use OrganizationLoanType.maxActiveLoans to limit "
                            + "concurrent loans of one type");
        }

        OrganizationLoanTypeExclusion exclusion = new OrganizationLoanTypeExclusion();
        exclusion.organization = organization;
        exclusion.loanTypeId = Math.min(firstLoanTypeId, secondLoanTypeId);
        exclusion.excludedLoanTypeId = Math.max(firstLoanTypeId, secondLoanTypeId);
        return exclusion;
    }

    /** Whether this rule concerns the given loan type, in either position. */
    public boolean involves(Long loanTypeId) {
        return loanTypeId != null
                && (loanTypeId.equals(this.loanTypeId)
                        || loanTypeId.equals(this.excludedLoanTypeId));
    }

    /**
     * The other half of the pair, given one half.
     *
     * @return the loan type the argument is incompatible with, or {@code null} if the argument is
     *         not part of this pair at all -- a question this rule has no answer to.
     */
    public Long counterpartOf(Long loanTypeId) {
        if (loanTypeId == null) {
            return null;
        }
        if (loanTypeId.equals(this.loanTypeId)) {
            return this.excludedLoanTypeId;
        }
        if (loanTypeId.equals(this.excludedLoanTypeId)) {
            return this.loanTypeId;
        }
        return null;
    }
}
