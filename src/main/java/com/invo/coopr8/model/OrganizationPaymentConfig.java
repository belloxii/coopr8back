package com.invo.coopr8.model;

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
 * Where one cooperative's money is settled, and by which provider. <strong>Exactly one row per
 * organization</strong>, enforced by {@code uk_organization_payment_config_organization} in V12.
 *
 * <p><strong>Nothing here is named after Paystack.</strong> {@link #providerAccountReference} holds
 * what Paystack calls a subaccount code when {@link #provider} is
 * {@link PaymentProviderName#PAYSTACK}; another provider's account handle occupies the same field.
 * The domain therefore reasons about "this cooperative's payment account", never about a Paystack
 * account, which is what lets a second provider be added without touching the tenant model.
 *
 * <p><strong>There is no credential on this entity, by design.</strong> No secret key, encrypted or
 * otherwise. The platform's Paystack secret is a server-side application secret and stays one; a
 * cooperative configures a settlement destination, never an API credential. Nothing on this entity
 * is safe to hand a browser either -- {@code providerAccountReference} is a settlement destination,
 * and the whole point of resolving it server-side is that the browser never names it.
 *
 * <p>The settlement fields are kept for one specific reason: they make an interrupted setup
 * <em>reconcilable</em>. If the provider creates an account and the response never reaches us, the
 * recorded account number is what lets the next attempt find the account that already exists
 * instead of creating a second one.
 */
@Filter(name = TenantFilter.NAME, condition = TenantFilter.CONDITION)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "organization_payment_config")
public class OrganizationPaymentConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Tenant owner. Isolation is enforced by scoped repository queries against this column; the
     * {@code @Filter} on the class is a secondary net -- see {@link TenantFilter}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    @JsonIgnore
    private Organization organization;

    /** Which provider settles this cooperative's money. */
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private PaymentProviderName provider;

    /** How the provider account is held. */
    @Enumerated(EnumType.STRING)
    @Column(name = "account_mode", nullable = false, length = 32)
    private PaymentAccountMode accountMode;

    /**
     * The provider's handle for this cooperative's account -- {@code ACCT_xxxxxxxx} for Paystack.
     * Null until the provider has actually issued one.
     *
     * <p>{@code uk_organization_payment_config_provider_account} makes this unique per provider, so
     * two cooperatives cannot point at the same account even if something above this layer tried.
     */
    @Column(name = "provider_account_reference", length = 255)
    private String providerAccountReference;

    /**
     * Whether this configuration may be used to take money. Only
     * {@link PaymentConfigStatus#ACTIVE} may, and V12 will not let the row reach {@code ACTIVE}
     * without a reference -- so a failed provider call cannot leave the cooperative looking
     * connected.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentConfigStatus status;

    /** Settlement bank, in the provider's own bank-code vocabulary. Not a credential. */
    @Column(name = "settlement_bank_code", length = 32)
    private String settlementBankCode;

    /** Settlement account number. The key an interrupted setup is reconciled by. */
    @Column(name = "settlement_account_number", length = 32)
    private String settlementAccountNumber;

    /** Settlement account name, as the bank holds it. */
    @Column(name = "settlement_account_name", length = 255)
    private String settlementAccountName;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Whether this configuration can be used to take money right now.
     *
     * <p>Both halves matter. A {@code PENDING} row has no account to settle into, and an
     * {@code ACTIVE} row in {@link PaymentAccountMode#NONE} has no destination either -- neither is
     * payable, and neither may fall back to the platform's own account.
     */
    public boolean isPayable() {
        return status == PaymentConfigStatus.ACTIVE
                && accountMode == PaymentAccountMode.PLATFORM_SUBACCOUNT
                && providerAccountReference != null
                && !providerAccountReference.isBlank();
    }
}
