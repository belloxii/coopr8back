package com.invo.coopr8.payment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.invo.coopr8.model.Organization;
import com.invo.coopr8.model.OrganizationPaymentConfig;
import com.invo.coopr8.model.PaymentConfigStatus;
import com.invo.coopr8.model.PaymentProviderName;
import com.invo.coopr8.security.CurrentAuth;
import com.invo.coopr8.service.OrganizationService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Connects a cooperative's own bank account as the destination its members' payments settle into.
 *
 * <p>This is what removes the provider's dashboard from onboarding: an administrator gives COOPR8 a
 * bank code and an account number, and COOPR8 creates the provider account itself.
 *
 * <h2>The cooperative is never a parameter</h2>
 * <strong>No method here accepts an organization.</strong> The tenant comes from
 * {@code CurrentAuth.requireAdmin()} -- the verified token -- and every store call is keyed by that id.
 * This is deliberate and structural: an administrator of one cooperative cannot attach another
 * cooperative's account, change another cooperative's provider, redirect another cooperative's payments
 * or read another cooperative's configuration, because there is no argument through which they could
 * name one. That is a stronger guarantee than validating an id would be, since there is nothing to
 * forget to validate.
 *
 * <h2>Why setup looks first and creates second</h2>
 * Creating a provider account is not idempotent -- there is no idempotency key COOPR8 can send that
 * makes "create this account" safe to repeat. So a request that times out after the provider created the
 * account but before COOPR8 stored the response leaves a genuinely ambiguous state, and a retry that
 * simply created again would leave two accounts and a cooperative's money split between them.
 *
 * <p>Three things together make the retry safe:
 * <ol>
 *   <li><strong>A committed claim before the provider is called.</strong> One row per organization, by
 *       unique index, so two concurrent attempts cannot both reach the provider -- and the row records
 *       the destination that was attempted.</li>
 *   <li><strong>A search before every create.</strong> {@link PaymentProvider#findTenantAccount} is
 *       asked whether the provider already holds an account for this destination. It does, so the
 *       retry adopts it and creates nothing.</li>
 *   <li><strong>An honest "I don't know".</strong> When the search cannot be completed, or finds more
 *       than one candidate, setup <em>refuses</em>. It does not create. The cooperative stays
 *       unconnected and reconcilable rather than becoming a duplicate.</li>
 * </ol>
 *
 * <p><strong>Documented limitation.</strong> A provider that offers neither an idempotency key nor a
 * lookup by settlement account cannot be reconciled automatically at all, and the bounded search here
 * can answer "indeterminate" for a merchant account holding a very large number of subaccounts. In both
 * cases COOPR8 stops and asks for a person, which is the point: an ambiguous provider response is
 * resolved by reconciliation, never by creating another account. There is no homemade distributed lock
 * anywhere in this flow -- the single unique index is the whole of the mutual exclusion.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantPaymentSetupService {

    /**
     * The provider new cooperatives are connected through.
     *
     * <p>A constant rather than a request parameter, because which provider the platform settles
     * through is a platform decision, not a tenant's -- and certainly not a browser's. When a second
     * implementation exists this becomes a configured value or an administrator's choice among the
     * <em>implemented</em> providers; until then, naming it here keeps the one place that has to change.
     */
    private static final PaymentProviderName PLATFORM_PROVIDER = PaymentProviderName.PAYSTACK;

    private final OrganizationService organizationService;
    private final PaymentProviderRegistry providerRegistry;
    private final TenantPaymentConfigStore configStore;

    /**
     * This cooperative's payment configuration, as far as an administrator is allowed to see it.
     *
     * <p>Read through the caller's own token, so there is no configuration but their own to read.
     */
    public Optional<TenantPaymentAccountView> currentPaymentAccount() {
        Long organizationId = CurrentAuth.requireAdmin().organizationId();
        return configStore.findOwn(organizationId).map(TenantPaymentAccountView::of);
    }

    /**
     * Connects the calling administrator's own cooperative to a provider account settling to
     * {@code submitted}.
     *
     * <p>Safe to call twice. An already-connected cooperative is returned unchanged -- this never
     * redirects a destination that is already taking money -- and an interrupted attempt is reconciled
     * rather than repeated.
     *
     * @throws ResponseStatusException        {@code 400} when the settlement details are unusable,
     *                                       {@code 502} when the provider could not be reached
     * @throws PaymentSetupConflictException {@code 409} when the state needs a person to resolve it
     */
    public TenantPaymentAccountView connectSettlementAccount(TenantSettlementDetails submitted) {
        Long organizationId = CurrentAuth.requireAdmin().organizationId();
        Organization organization = organizationService.currentOrganizationEntity();

        TenantSettlementDetails destination = validated(submitted);

        OrganizationPaymentConfig configuration = configStore.findOwn(organizationId)
                .orElseGet(() -> configStore.claim(organization, PLATFORM_PROVIDER, destination));

        if (configuration.getStatus() == PaymentConfigStatus.ACTIVE) {
            // Already connected. Repeated setup is a no-op, not a second account, and not a change of
            // destination -- moving where settled money lands is not something this call may do.
            return TenantPaymentAccountView.of(configuration);
        }
        if (configuration.getStatus() != PaymentConfigStatus.PENDING) {
            throw new PaymentSetupConflictException(
                    "This cooperative's online payments have been disabled. "
                            + "Please contact COOPR8 support.", null);
        }

        PaymentProvider provider = providerRegistry.forProvider(configuration.getProvider());
        TenantSettlementDetails recorded = recordedDestination(configuration);

        // Reconcile before creating. The destination of the interrupted attempt is checked first,
        // because that is the one the provider may already hold an account for.
        for (TenantSettlementDetails candidate : reconciliationOrder(recorded, destination)) {
            ProviderAccountSearch search = provider.findTenantAccount(
                    new ProviderAccountLookup(candidate.bankCode(), candidate.accountNumber()));

            switch (search.outcome()) {
                case FOUND -> {
                    log.info("Adopting an existing {} account for organization {} rather than "
                            + "creating a second one.", provider.providerName(), organizationId);
                    return TenantPaymentAccountView.of(configStore.recordProviderAccount(
                            organizationId, candidate, search.providerAccountReference(), true));
                }
                case AMBIGUOUS -> throw new PaymentSetupConflictException(
                        "The payment provider already holds more than one account for this bank "
                                + "account. COOPR8 will not choose between them -- please contact "
                                + "support to have them reconciled.", null);
                case INDETERMINATE -> throw new PaymentSetupConflictException(
                        "COOPR8 could not confirm whether a payment account already exists for this "
                                + "bank account, so it will not create one. Please try again shortly.",
                        null);
                case NONE -> {
                    // Nothing exists for this candidate. Keep looking, then create.
                }
            }
        }

        if (!destination.equals(recorded)) {
            // An earlier attempt recorded a different destination and the provider holds nothing for
            // it, so replacing it cannot orphan an account.
            log.info("Replacing the intended settlement destination for organization {} after "
                    + "confirming the provider holds no account for the previous one.", organizationId);
            configStore.replaceIntendedDestination(organizationId, destination);
        }

        ProviderAccountResult created;
        try {
            created = provider.createTenantAccount(new ProviderAccountRequest(
                    organizationService.displayName(organization),
                    destination.bankCode(),
                    destination.accountNumber(),
                    destination.accountName(),
                    organization.getEmail()));

        } catch (PaymentProviderException failed) {
            // The configuration stays PENDING with no reference, so the cooperative is not connected
            // and no payment can be started against it. The next attempt will search before creating.
            log.error("Provider {} would not create a settlement account for organization {}.",
                    provider.providerName(), organizationId, failed);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The payment provider could not set up this cooperative's account right now. "
                            + "Please try again shortly.");
        }

        OrganizationPaymentConfig recordedAccount = configStore.recordProviderAccount(
                organizationId, destination, created.providerAccountReference(), created.active());

        if (!created.active()) {
            throw new PaymentSetupConflictException(
                    "The payment provider created this cooperative's account but has not activated "
                            + "it yet. COOPR8 has recorded it and will not create another; please "
                            + "check back shortly.", null);
        }
        return TenantPaymentAccountView.of(recordedAccount);
    }

    // ------------------------------------------------------------------------- internals

    /**
     * The destinations to search before creating: what the interrupted attempt recorded, then what has
     * just been submitted if it differs.
     */
    private static List<TenantSettlementDetails> reconciliationOrder(
            TenantSettlementDetails recorded, TenantSettlementDetails submitted) {

        List<TenantSettlementDetails> order = new ArrayList<>(2);
        if (recorded != null) {
            order.add(recorded);
        }
        if (recorded == null || !sameAccount(recorded, submitted)) {
            order.add(submitted);
        }
        return order;
    }

    private static boolean sameAccount(TenantSettlementDetails one, TenantSettlementDetails other) {
        return one.accountNumber().equals(other.accountNumber())
                && one.bankCode().equals(other.bankCode());
    }

    /** The destination a previous attempt recorded, or null when it recorded none. */
    private static TenantSettlementDetails recordedDestination(OrganizationPaymentConfig config) {
        if (config.getSettlementBankCode() == null || config.getSettlementAccountNumber() == null) {
            return null;
        }
        return new TenantSettlementDetails(
                config.getSettlementBankCode(),
                config.getSettlementAccountNumber(),
                config.getSettlementAccountName());
    }

    /**
     * Settlement details COOPR8 is willing to send a provider.
     *
     * <p>Trimmed, and refused when empty. The account number is required to be digits only: a provider
     * would reject anything else anyway, and finding that out after a claim row has been committed
     * turns a typo into a {@code PENDING} row somebody has to clear.
     */
    private static TenantSettlementDetails validated(TenantSettlementDetails submitted) {
        if (submitted == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Provide the cooperative's settlement bank and account number.");
        }

        String bankCode = trimmed(submitted.bankCode());
        String accountNumber = trimmed(submitted.accountNumber());
        String accountName = trimmed(submitted.accountName());

        if (bankCode == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a settlement bank.");
        }
        if (accountNumber == null || !accountNumber.chars().allMatch(Character::isDigit)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Enter the settlement account number, digits only.");
        }
        return new TenantSettlementDetails(bankCode, accountNumber, accountName);
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
