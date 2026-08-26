package com.invo.coopr8.payment;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.invo.coopr8.model.Organization;
import com.invo.coopr8.model.OrganizationPaymentConfig;
import com.invo.coopr8.model.PaymentAccountMode;
import com.invo.coopr8.model.PaymentConfigStatus;
import com.invo.coopr8.model.PaymentProviderName;
import com.invo.coopr8.repository.OrganizationPaymentConfigRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The three database steps of connecting a cooperative's payment account, each in its own transaction.
 *
 * <p><strong>This is a separate bean for a reason that is easy to get wrong.</strong>
 * {@link TenantPaymentSetupService} must <em>commit</em> its claim before it calls the provider, and
 * must run the provider call outside any transaction -- an HTTP call to a payment provider inside an
 * open transaction holds a database connection and a row lock for the provider's latency, and a
 * provider that stalls would take a connection out of the pool for the read timeout. Spring's
 * {@code @Transactional} is proxy-based, so a method calling another method on {@code this} gets no new
 * transaction at all; splitting the transactional steps into this collaborator is what makes the
 * boundaries real rather than decorative.
 *
 * <p><strong>Every method here is keyed by organization id and nothing else.</strong> There is no
 * method that takes a configuration id, because a configuration id is a number an administrator could
 * substitute -- and one row per organization means an id would add nothing but that risk. The caller
 * supplies an id that came from a verified token, so a tenant can only ever reach its own row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantPaymentConfigStore {

    private final OrganizationPaymentConfigRepository paymentConfigRepository;

    /** This cooperative's payment configuration, if it has one. */
    @Transactional(readOnly = true)
    public Optional<OrganizationPaymentConfig> findOwn(Long organizationId) {
        return paymentConfigRepository.findByOrganizationId(organizationId);
    }

    /**
     * Records that this cooperative is being connected, before the provider is contacted.
     *
     * <p>Written first, and committed, so that:
     * <ul>
     *   <li>two administrators clicking "connect" at once cannot both call the provider --
     *       {@code uk_organization_payment_config_organization} lets exactly one of them past, and the
     *       other is told setup is already under way rather than creating a second account;</li>
     *   <li>an attempt that is interrupted after the provider acts leaves a record of <em>what
     *       destination was attempted</em>, which is the key the next attempt reconciles by.</li>
     * </ul>
     *
     * <p>The row is {@link PaymentConfigStatus#PENDING} with no provider reference, and V12's
     * {@code ck_organization_payment_config_active_reference} means it cannot be mistaken for a
     * connected cooperative: {@link OrganizationPaymentConfig#isPayable()} is false and no payment can
     * be started against it.
     *
     * @throws PaymentSetupConflictException when another attempt got there first
     */
    @Transactional
    public OrganizationPaymentConfig claim(
            Organization organization,
            PaymentProviderName provider,
            TenantSettlementDetails destination) {

        OrganizationPaymentConfig claim = OrganizationPaymentConfig.builder()
                .organization(organization)
                .provider(provider)
                .accountMode(PaymentAccountMode.PLATFORM_SUBACCOUNT)
                .status(PaymentConfigStatus.PENDING)
                .settlementBankCode(destination.bankCode())
                .settlementAccountNumber(destination.accountNumber())
                .settlementAccountName(destination.accountName())
                .build();

        try {
            return paymentConfigRepository.saveAndFlush(claim);
        } catch (DataIntegrityViolationException alreadyClaimed) {
            // The unique index did its job. Whoever won is now talking to the provider.
            throw new PaymentSetupConflictException(
                    "Payment setup for this cooperative is already under way. "
                            + "Please try again in a moment.",
                    alreadyClaimed);
        }
    }

    /**
     * Records the account the provider issued -- or the one it turned out to already hold -- against
     * this cooperative.
     *
     * <p>This is the only method that writes {@link PaymentConfigStatus#ACTIVE}, and it is reached only
     * once a provider account reference exists. A provider call that failed never gets here, so a
     * failure cannot leave a cooperative looking connected; V12's
     * {@code ck_organization_payment_config_active_reference} enforces the same rule at the database, so
     * no future code path can either.
     *
     * <p>{@code destination} is written alongside the reference because the two must agree: the
     * recorded destination is what a later reconciliation searches by, and a reference adopted for one
     * account number but recorded against another would send the next attempt looking in the wrong
     * place.
     *
     * <p>The reference is recorded <strong>even when {@code usable} is false</strong> -- an account the
     * provider created but reports as not yet usable still exists, and forgetting its handle is how a
     * cooperative ends up with a second one. It stays {@code PENDING} in that case, so it exists on the
     * record without being payable.
     *
     * <p>Re-running with the same reference is a no-op rather than an error, so a retry that races
     * itself settles instead of failing.
     */
    @Transactional
    public OrganizationPaymentConfig recordProviderAccount(
            Long organizationId,
            TenantSettlementDetails destination,
            String providerAccountReference,
            boolean usable) {

        OrganizationPaymentConfig configuration = paymentConfigRepository
                .findByOrganizationId(organizationId)
                .orElseThrow(() -> new IllegalStateException(
                        "Payment configuration for organization " + organizationId
                                + " disappeared during setup."));

        if (configuration.getStatus() == PaymentConfigStatus.ACTIVE) {
            if (providerAccountReference.equals(configuration.getProviderAccountReference())) {
                return configuration;
            }
            // Two different accounts for one cooperative. Never silently redirect where money lands.
            throw new PaymentSetupConflictException(
                    "This cooperative is already connected to a different payment account.", null);
        }
        if (configuration.getStatus() != PaymentConfigStatus.PENDING) {
            throw new PaymentSetupConflictException(
                    "This cooperative's payment configuration is not awaiting setup.", null);
        }

        configuration.setAccountMode(PaymentAccountMode.PLATFORM_SUBACCOUNT);
        configuration.setProviderAccountReference(providerAccountReference);
        configuration.setSettlementBankCode(destination.bankCode());
        configuration.setSettlementAccountNumber(destination.accountNumber());
        configuration.setSettlementAccountName(destination.accountName());
        if (usable) {
            configuration.setStatus(PaymentConfigStatus.ACTIVE);
        }

        OrganizationPaymentConfig saved = paymentConfigRepository.saveAndFlush(configuration);
        if (usable) {
            log.info("Organization {} is now connected for {} payments.",
                    organizationId, saved.getProvider());
        } else {
            log.warn("Organization {} has a {} account on record that the provider does not report as "
                    + "usable. Left unconnected.", organizationId, saved.getProvider());
        }
        return saved;
    }

    /**
     * Replaces the destination an unconnected cooperative is being set up for.
     *
     * <p>Only ever called after the provider has confirmed that <em>nothing</em> exists for the
     * previously recorded destination -- which is what makes it safe. It exists so that an
     * administrator who mistyped an account number on a failed attempt can correct it, instead of
     * being permanently stuck with a {@code PENDING} row nobody can move.
     *
     * <p>It cannot touch a connected cooperative: an {@code ACTIVE} row is refused here, so this is
     * not a back door to redirecting settled money.
     */
    @Transactional
    public OrganizationPaymentConfig replaceIntendedDestination(
            Long organizationId, TenantSettlementDetails destination) {

        OrganizationPaymentConfig configuration = paymentConfigRepository
                .findByOrganizationId(organizationId)
                .orElseThrow(() -> new IllegalStateException(
                        "Payment configuration for organization " + organizationId
                                + " disappeared during setup."));

        if (configuration.getStatus() != PaymentConfigStatus.PENDING) {
            throw new PaymentSetupConflictException(
                    "This cooperative's settlement destination cannot be changed here.", null);
        }

        configuration.setSettlementBankCode(destination.bankCode());
        configuration.setSettlementAccountNumber(destination.accountNumber());
        configuration.setSettlementAccountName(destination.accountName());
        return paymentConfigRepository.saveAndFlush(configuration);
    }
}
