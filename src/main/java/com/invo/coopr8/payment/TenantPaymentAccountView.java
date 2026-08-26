package com.invo.coopr8.payment;

import com.invo.coopr8.model.OrganizationPaymentConfig;
import com.invo.coopr8.model.PaymentAccountMode;
import com.invo.coopr8.model.PaymentConfigStatus;
import com.invo.coopr8.model.PaymentProviderName;

/**
 * One cooperative's payment setup, as something safe to hand outside the service.
 *
 * <p>The entity is not returned in its place because it carries a live reference to the
 * {@code Organization} and would serialize a tenant's neighbours into a response the first time
 * someone added a getter. This record carries only what a cooperative's own administrator may see
 * about their own setup.
 *
 * <p>There is no field for anyone else's configuration and no way to build one for another tenant:
 * every instance is produced from a row already fetched by organization id.
 *
 * @param provider                 which provider settles this cooperative's payments.
 * @param accountMode              how the destination is modelled -- see {@link PaymentAccountMode}.
 * @param status                   whether the setup is usable.
 * @param providerAccountReference the destination account at the provider, or null before one exists.
 * @param settlementBankCode       the settlement bank code on file, or null.
 * @param settlementAccountNumber  the settlement account number on file, or null.
 * @param settlementAccountName    the settlement account name on file, or null.
 */
public record TenantPaymentAccountView(
        PaymentProviderName provider,
        PaymentAccountMode accountMode,
        PaymentConfigStatus status,
        String providerAccountReference,
        String settlementBankCode,
        String settlementAccountNumber,
        String settlementAccountName) {

    public static TenantPaymentAccountView of(OrganizationPaymentConfig config) {
        return new TenantPaymentAccountView(
                config.getProvider(),
                config.getAccountMode(),
                config.getStatus(),
                config.getProviderAccountReference(),
                config.getSettlementBankCode(),
                config.getSettlementAccountNumber(),
                config.getSettlementAccountName());
    }

    /** Whether a payment can actually be routed with this setup. */
    public boolean payable() {
        return status == PaymentConfigStatus.ACTIVE
                && accountMode == PaymentAccountMode.PLATFORM_SUBACCOUNT
                && providerAccountReference != null
                && !providerAccountReference.isBlank();
    }
}
