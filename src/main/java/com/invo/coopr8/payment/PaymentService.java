package com.invo.coopr8.payment;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.invo.coopr8.dto.PaymentInitializationRequest;
import com.invo.coopr8.dto.PaymentInitializationResponse;
import com.invo.coopr8.dto.PaymentVerificationResponse;
import com.invo.coopr8.model.Organization;
import com.invo.coopr8.model.OrganizationPaymentConfig;
import com.invo.coopr8.model.PaymentProviderName;
import com.invo.coopr8.model.PaymentPurpose;
import com.invo.coopr8.model.PaymentTransaction;
import com.invo.coopr8.model.PaymentType;
import com.invo.coopr8.model.User;
import com.invo.coopr8.repository.OrganizationPaymentConfigRepository;
import com.invo.coopr8.repository.PaymentTransactionRepository;
import com.invo.coopr8.security.CurrentAuth;
import com.invo.coopr8.service.OrganizationService;
import com.invo.coopr8.service.UserService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Online payments, from the domain's point of view.
 *
 * <p><strong>Nothing in this class knows which provider it is talking to.</strong> It resolves a
 * {@link PaymentProvider} and speaks to it through the interface; the concrete implementation is
 * chosen by the paying cooperative's own configuration. A second provider is a new implementation
 * bean and a configuration row, not a change here.
 *
 * <h2>Where a payment goes</h2>
 * The destination is derived, never accepted:
 * <pre>
 *   verified token -> member -> member's cooperative -> that cooperative's payment configuration
 *                  -> its provider -> its provider account reference
 * </pre>
 * Every arrow is a database read. The request body contributes the amount and what the payment is
 * for, and nothing else -- there is no field on {@link PaymentInitializationRequest} that names an
 * organization, a provider or a settlement account, so a browser posting
 * {@code {"organizationId": 7, "subaccount": "ACCT_someone_else"}} has posted two fields that are
 * discarded by the deserializer. A member of one cooperative cannot route a payment to another
 * cooperative's account because nothing in the request is consulted when choosing it.
 *
 * <h2>A cooperative with no payment configuration</h2>
 * Refused. Not defaulted to the platform's own account, not defaulted to the only configured
 * cooperative, not left to the provider to place. A missing configuration means nobody has said where
 * this cooperative's members' money should go, and the safe answer to that is to take none.
 *
 * <h2>The callback</h2>
 * See {@link #handleProviderCallback}: the signature is checked against the exact bytes received
 * before anything is parsed, and the cooperative is read from COOPR8's own payment row rather than
 * from the callback.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final UserService userService;
    private final OrganizationService organizationService;
    private final OrganizationPaymentConfigRepository paymentConfigRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentProviderRegistry providerRegistry;
    private final PaymentReferenceGenerator referenceGenerator;
    private final PaymentPostingService postingService;

    // ------------------------------------------------------------------- starting a payment

    /**
     * Starts a payment for the authenticated member, through their own cooperative's provider account.
     *
     * @return a checkout to send the payer to, or a refusal explaining why not. Refusals are
     *         deliberately ordinary responses rather than exceptions: "your cooperative has not
     *         finished payment setup" is something a member should be told, not a stack trace.
     */
    public PaymentInitializationResponse initializePayment(PaymentInitializationRequest request) {
        User user = userService.requireCurrentUser();
        Organization organization = organizationService.requireForUser(user);

        if (user.getPaymentType() == PaymentType.GOVERNMENT) {
            return PaymentInitializationResponse.refused(
                    "Your savings and repayments are deducted from your salary automatically. "
                            + "Online payment is not required.");
        }

        Map<String, Object> metadata = request.getMetadata() == null
                ? Map.of()
                : request.getMetadata();

        PaymentPurpose purpose = PaymentPurpose
                .fromRequestType(asText(metadata.get("type")))
                .orElse(null);
        if (purpose == null) {
            return PaymentInitializationResponse.refused(
                    "This payment does not say what it is for.");
        }

        BigDecimal amount = nairaFromKobo(request.getAmount());
        if (amount == null) {
            return PaymentInitializationResponse.refused("Enter an amount greater than zero.");
        }

        Long targetId = null;
        if (purpose == PaymentPurpose.REPAYMENT) {
            targetId = asLong(metadata.get("loanId"));
            if (targetId == null) {
                return PaymentInitializationResponse.refused(
                        "This repayment does not say which loan it is for.");
            }
        }

        if (purpose == PaymentPurpose.SAVINGS) {
            // A savings payment credits the member's plan rather than the amount tendered, so the
            // two have to agree. Refusing here rather than at the callback is what keeps a
            // mismatched payment from being taken and then left uncreditable.
            BigDecimal plan = user.getSavingPlan();
            if (plan == null) {
                return PaymentInitializationResponse.refused(
                        "Set your monthly savings plan before saving online.");
            }
            if (amount.compareTo(plan) != 0) {
                return PaymentInitializationResponse.refused(
                        "The amount must match your monthly savings plan.");
            }
        }

        // Where the money goes. Read from this cooperative's own row, keyed by the organization the
        // token resolved to.
        OrganizationPaymentConfig configuration = paymentConfigRepository
                .findByOrganizationId(organization.getId())
                .orElse(null);

        if (configuration == null || !configuration.isPayable()) {
            log.warn("Refusing to start a payment for organization {}: payment configuration is {}.",
                    organization.getId(),
                    configuration == null ? "absent" : "not usable (" + configuration.getStatus()
                            + "/" + configuration.getAccountMode() + ")");
            return PaymentInitializationResponse.refused(
                    "This cooperative has not finished setting up online payments. "
                            + "Please contact your cooperative's administrator.");
        }

        PaymentProvider provider;
        try {
            provider = providerRegistry.forProvider(configuration.getProvider());
        } catch (PaymentProviderException unsupported) {
            // A configuration naming a provider with no implementation. Refused rather than settled
            // through whichever provider happens to be available.
            log.error("Organization {} is configured for a payment provider COOPR8 cannot use.",
                    organization.getId(), unsupported);
            return PaymentInitializationResponse.refused(
                    "Online payments are unavailable for this cooperative right now.");
        }

        String reference = referenceGenerator.generate();

        // Committed before the provider is contacted, so the callback that follows has a row to
        // resolve its cooperative and its member from.
        PaymentTransaction reserved;
        try {
            reserved = postingService.reserve(user, organization, provider.providerName(),
                    reference, purpose, targetId, amount);
        } catch (PaymentReferenceInUseException collision) {
            log.error("Refusing to start a payment: reference collision.", collision);
            return PaymentInitializationResponse.refused(
                    "That payment could not be started. Please try again.");
        }

        try {
            ProviderCheckoutResult checkout = provider.initializeCheckout(new ProviderCheckoutRequest(
                    reference,
                    amount,
                    user.getEmail(),
                    request.getCallback_url(),
                    configuration.getProviderAccountReference(),
                    providerMetadata(organization, purpose)));

            return PaymentInitializationResponse.accepted(
                    checkout.checkoutUrl(), checkout.accessCode(), checkout.providerReference());

        } catch (PaymentProviderException failed) {
            // The reserved row stays PENDING. It will never be paid, and it records that a payment
            // was attempted -- which is more useful than deleting the evidence.
            log.error("Provider {} would not start payment {} for organization {}.",
                    provider.providerName(), reserved.getId(), organization.getId(), failed);
            return PaymentInitializationResponse.refused(
                    "The payment could not be started. Please try again shortly.");
        }
    }

    /**
     * Whether a payment the calling member started went through.
     *
     * <p>Scoped to the caller before the provider is asked, so a member cannot use this to learn the
     * outcome of a fellow member's -- or another cooperative's -- payment. An unknown reference and
     * somebody else's reference are the same {@code 404}.
     *
     * <p>The provider is asked rather than COOPR8's own row, because a payer's browser usually
     * returns before the callback arrives; the row would say "pending" for a payment that succeeded.
     * Nothing financial happens here either way.
     */
    public PaymentVerificationResponse verifyPayment(String reference) {
        var principal = CurrentAuth.require();

        PaymentTransaction payment = paymentTransactionRepository
                .findOwnPaymentWithinOrganization(
                        reference, principal.organizationId(), principal.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "That payment could not be found."));

        PaymentProvider provider = providerRegistry.forProvider(payment.getProvider());
        ProviderVerificationResult verified = provider.verifyPayment(reference);

        return PaymentVerificationResponse.of(verified.paid());
    }

    // ----------------------------------------------------------------------- the callback

    /**
     * Acts on a provider's callback.
     *
     * <p>The order of the first two steps is the security property:
     * <ol>
     *   <li>The signature is checked against the exact bytes that arrived. No parse, no
     *       reserialization, no field access -- a body whose signature does not match is never
     *       interpreted at all.</li>
     *   <li>Only then is the reference read out of it.</li>
     * </ol>
     *
     * <p>Then the provider is asked, over an authenticated call of COOPR8's own making, whether the
     * money was actually taken. The callback body supplies one thing: a reference to look up.
     *
     * @param rawBody         the request body, byte for byte as received.
     * @param signatureHeader the provider's signature header, or null when it was absent.
     */
    public WebhookOutcome handleProviderCallback(
            PaymentProviderName providerName, byte[] rawBody, String signatureHeader) {

        PaymentProvider provider = providerRegistry.forProvider(providerName);

        if (signatureHeader == null || signatureHeader.isBlank()) {
            log.warn("Rejected a {} callback with no signature.", providerName);
            return WebhookOutcome.REJECTED;
        }
        if (rawBody == null || !provider.signatureMatches(rawBody, signatureHeader)) {
            log.warn("Rejected a {} callback whose signature did not match.", providerName);
            return WebhookOutcome.REJECTED;
        }

        Optional<String> reference = provider.successfulPaymentReference(rawBody);
        if (reference.isEmpty()) {
            // Authentic, but not a successful charge. Providers send a great many other events.
            return WebhookOutcome.IGNORED;
        }

        ProviderVerificationResult verified = provider.verifyPayment(reference.get());
        if (!verified.paid()) {
            log.warn("A {} callback reported a payment the provider's own verification does not "
                    + "confirm. Nothing processed.", providerName);
            return WebhookOutcome.UNCONFIRMED;
        }

        return postingService.post(providerName, reference.get(), verified.amount());
    }

    // ------------------------------------------------------------------------- internals

    /**
     * What COOPR8 tells the provider about this payment, for the provider's own dashboard.
     *
     * <p>Assembled here from values COOPR8 has already established. The caller's metadata is not
     * forwarded, and nothing in here is ever read back: the callback path resolves everything from
     * COOPR8's database, so provider metadata is a convenience for whoever is reconciling in the
     * provider's console and carries no authority.
     */
    private Map<String, String> providerMetadata(Organization organization, PaymentPurpose purpose) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("purpose", purpose.requestType());
        metadata.put("cooperative", organization.getSlug());
        return metadata;
    }

    /** Kobo, as the frontend sends it, to naira. Null when there is nothing to charge. */
    private static BigDecimal nairaFromKobo(long kobo) {
        if (kobo <= 0) {
            return null;
        }
        return BigDecimal.valueOf(kobo).movePointLeft(2);
    }

    private static String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static Long asLong(Object value) {
        String text = asText(value);
        if (text == null) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
