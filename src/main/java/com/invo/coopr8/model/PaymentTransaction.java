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
 * One payment COOPR8 started, and COOPR8's own record of who it belongs to.
 *
 * <p><strong>This entity is the answer to two separate defects.</strong>
 *
 * <p><strong>Tenant identity.</strong> The organization a payment belonged to used to be recovered
 * from metadata carried on the provider's transaction -- a provider-side hint about a COOPR8 fact.
 * This row is written <em>before</em> the provider is called, with a reference COOPR8 generated
 * itself, so by the time any webhook can arrive the record it names already exists. The webhook
 * therefore resolves the cooperative <em>and</em> the member from here, consulting neither the
 * inbound body nor the provider's metadata nor the payer's email address. A webhook naming a
 * reference with no row is not guessed at; it is ignored.
 *
 * <p><strong>Idempotency.</strong> {@code uk_payment_transaction_provider_reference} in V12 makes
 * {@code (provider, providerReference)} unique, and {@link #status} moves from
 * {@link PaymentStatus#PENDING} to {@link PaymentStatus#SUCCEEDED} by a conditional update that only
 * one caller can win. The claim and the credit share a transaction, so a replayed provider event
 * finds nothing left to claim and credits nothing.
 *
 * <p>{@code fk_payment_transaction_member} is composite -- {@code (organization_id, user_id)} against
 * {@code users (organization_id, id)} -- so the database itself refuses a payment whose payer sits
 * in one cooperative and whose money sits in another.
 */
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "payment_transaction")
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The cooperative this payment belongs to, decided from the authenticated caller's token at
     * initialization and never re-decided afterwards.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    @JsonIgnore
    private Organization organization;

    /** The paying member. Guaranteed by composite foreign key to belong to {@link #organization}. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    /** Which provider is taking the money. */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private PaymentProviderName provider;

    /**
     * The reference the provider knows this payment by.
     *
     * <p>COOPR8 generates it and supplies it to the provider, rather than adopting whatever the
     * provider would have generated. That ordering is the whole design: the authoritative row exists
     * before the provider call is made, so there is no window in which a webhook could arrive for a
     * payment COOPR8 has no record of.
     */
    @Column(name = "provider_reference", nullable = false, length = 255)
    private String providerReference;

    /** Which ledger a verified payment credits. */
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 32)
    private PaymentPurpose purpose;

    /**
     * The loan a {@link PaymentPurpose#REPAYMENT} pays, and null otherwise. Required for repayments
     * by {@code ck_payment_transaction_repayment_target}.
     *
     * <p>Not a foreign key: the loan is already reachable through the tenant-scoped loan repository,
     * and the repayment path validates that the loan belongs to this member within this cooperative
     * before crediting anything.
     */
    @Column(name = "target_id")
    private Long targetId;

    /** The amount the member asked to pay, in naira. What the provider actually took is verified. */
    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    /** Whether this payment has been credited. See the class comment. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentStatus status;

    /** When it was credited. Non-null exactly when {@link #status} is succeeded. */
    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
