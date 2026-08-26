package com.invo.coopr8.payment.paystack;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invo.coopr8.model.PaymentProviderName;
import com.invo.coopr8.payment.PaymentProvider;
import com.invo.coopr8.payment.PaymentProviderException;
import com.invo.coopr8.payment.ProviderAccountLookup;
import com.invo.coopr8.payment.ProviderAccountRequest;
import com.invo.coopr8.payment.ProviderAccountResult;
import com.invo.coopr8.payment.ProviderAccountSearch;
import com.invo.coopr8.payment.ProviderCheckoutRequest;
import com.invo.coopr8.payment.ProviderCheckoutResult;
import com.invo.coopr8.payment.ProviderVerificationResult;

import lombok.extern.slf4j.Slf4j;

/**
 * Paystack, expressed as a {@link PaymentProvider}.
 *
 * <p>The first -- and currently only -- implementation of the boundary. It exists so that the rest of
 * COOPR8 can take money without knowing that Paystack is what takes it: this class and
 * {@link PaystackApiClient} are the whole of the platform's Paystack knowledge, and
 * {@code TenantIsolationArchitectureTest} keeps it that way by failing the build if anything outside
 * this package names a class in it.
 *
 * <p>The division of labour between the two is deliberate. {@link PaystackApiClient} owns Paystack's
 * <em>HTTP</em> contract -- paths, field names, kobo. This class owns Paystack's <em>callback</em>
 * contract -- the signature scheme and the event vocabulary -- and the mapping from Paystack's answers
 * to the platform's neutral vocabulary. Neither leaks a Paystack type past the interface.
 *
 * <p>A second provider is a sibling of this class. Nothing here is a base class, a template method or
 * an extension point, because a second implementation of a five-method interface does not need one.
 */
@Slf4j
@Component
public class PaystackPaymentProvider implements PaymentProvider {

    // --------------------------------------------------------------- the callback contract

    /**
     * The signature scheme Paystack uses for webhooks: HMAC-SHA512 over the exact request body,
     * keyed by the merchant's secret key, presented as lowercase hex in {@code x-paystack-signature}.
     */
    private static final String SIGNATURE_ALGORITHM = "HmacSHA512";

    /** The one event that means a member's money moved. Everything else is not COOPR8's business. */
    private static final String SUCCESSFUL_CHARGE_EVENT = "charge.success";

    // ------------------------------------------------------------------- collaborators

    private final PaystackApiClient apiClient;
    private final ObjectMapper objectMapper;

    /**
     * The platform's Paystack secret, which is also the webhook signing key.
     *
     * <p>From configuration, held in this field, and never logged, returned, or stored against a
     * cooperative. A tenant does not have one of these and is never asked for one: COOPR8's tenants
     * receive money through settlement accounts under the platform's merchant account, so there is no
     * per-tenant credential to leak in the first place.
     */
    private final byte[] signingKey;

    public PaystackPaymentProvider(
            PaystackApiClient apiClient,
            ObjectMapper objectMapper,
            @Value("${paystack.secret.key}") String secretKey) {

        this.apiClient = apiClient;
        this.objectMapper = objectMapper;
        this.signingKey = secretKey == null
                ? new byte[0]
                : secretKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public PaymentProviderName providerName() {
        return PaymentProviderName.PAYSTACK;
    }

    // ---------------------------------------------------------------- taking a payment

    @Override
    public ProviderCheckoutResult initializeCheckout(ProviderCheckoutRequest request) {
        return apiClient.initializeTransaction(request);
    }

    @Override
    public ProviderVerificationResult verifyPayment(String providerReference) {
        return apiClient.verifyTransaction(providerReference);
    }

    // ------------------------------------------------- the cooperative's own account

    @Override
    public ProviderAccountResult createTenantAccount(ProviderAccountRequest request) {
        return apiClient.createSubaccount(request);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Paystack offers no way to fetch a subaccount by settlement account number, so this
     * enumerates. That is why the outcome can be {@code INDETERMINATE}: the enumeration is bounded,
     * and a bounded search that ran out of pages has not proved absence. The mapping from the client's
     * answer is the whole of the safety property here --
     * {@code null} (could not finish) becomes {@code INDETERMINATE}, never {@code NONE}.
     */
    @Override
    public ProviderAccountSearch findTenantAccount(ProviderAccountLookup lookup) {
        List<String> matches;
        try {
            matches = apiClient.findSubaccountCodesByAccountNumber(lookup.accountNumber());
        } catch (PaymentProviderException unreachable) {
            log.warn("Could not search Paystack subaccounts; reporting the search as inconclusive.",
                    unreachable);
            return ProviderAccountSearch.indeterminate();
        }

        if (matches == null) {
            return ProviderAccountSearch.indeterminate();
        }
        if (matches.isEmpty()) {
            return ProviderAccountSearch.none();
        }
        if (matches.size() > 1) {
            // Two accounts settling to one bank account. Which one a cooperative's members should pay
            // into is not a question code gets to answer.
            log.error("Paystack holds {} subaccounts settling to the same account number. "
                    + "Refusing to choose one.", matches.size());
            return ProviderAccountSearch.ambiguous();
        }
        return ProviderAccountSearch.found(matches.get(0));
    }

    // ------------------------------------------------------------------- the callback

    /**
     * {@inheritDoc}
     *
     * <p>HMAC-SHA512 over {@code rawBody} exactly as it arrived, keyed by the platform secret, compared
     * against the header in constant time.
     *
     * <p>Three details are load-bearing:
     * <ul>
     *   <li><strong>The bytes are the ones received.</strong> Nothing parses or re-serializes the body
     *       first. A signature covers the bytes the provider signed; a JSON round trip can reorder
     *       keys, change number formatting or re-escape strings, and the result would fail
     *       verification for authentic requests and -- if the round trip were normalising -- could pass
     *       it for altered ones.</li>
     *   <li><strong>The comparison is constant time.</strong> {@link MessageDigest#isEqual} rather than
     *       {@link String#equals}, which returns at the first differing character and so leaks how much
     *       of a guessed signature was right. That leak is enough to forge a signature byte by byte.</li>
     *   <li><strong>An absent or malformed signature is a failure.</strong> Not a skipped check.</li>
     * </ul>
     *
     * <p>The header is lower-cased before comparison, which costs nothing in secrecy: the case fold is
     * applied to the value the caller supplied, not to the computed one.
     */
    @Override
    public boolean signatureMatches(byte[] rawBody, String signatureHeader) {
        if (rawBody == null || signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        if (signingKey.length == 0) {
            // No configured secret means every signature is unverifiable, which must read as "reject".
            // Accepting here would turn a misconfiguration into an open financial endpoint.
            log.error("No Paystack secret is configured; rejecting the callback.");
            return false;
        }

        byte[] expected = hexSignature(rawBody).getBytes(StandardCharsets.US_ASCII);
        byte[] presented = signatureHeader.trim()
                .toLowerCase(Locale.ROOT)
                .getBytes(StandardCharsets.US_ASCII);

        return MessageDigest.isEqual(expected, presented);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads two fields and no more: the event name, and the reference. Amount, customer, card and
     * channel are all present in a Paystack callback and all ignored, because a callback is an
     * unauthenticated statement about money -- {@link #verifyPayment} is where COOPR8 finds out what
     * really happened.
     *
     * <p>An unparseable body returns empty rather than throwing. By the time this is called the
     * signature has already matched, so a body that will not parse is COOPR8's problem with its own
     * provider rather than an attack, and answering "nothing to do" is the safe reading.
     */
    @Override
    public Optional<String> successfulPaymentReference(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            return Optional.empty();
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(rawBody);
        } catch (Exception unparseable) {
            log.warn("A signature-verified Paystack callback could not be parsed. Ignored.");
            return Optional.empty();
        }

        if (!SUCCESSFUL_CHARGE_EVENT.equals(text(payload, "event"))) {
            // Paystack sends many events -- transfers, disputes, subscriptions, failed charges.
            // COOPR8 acts on one.
            return Optional.empty();
        }

        String reference = text(payload.path("data"), "reference");
        if (reference == null || reference.isBlank()) {
            log.warn("A Paystack charge.success callback carried no reference. Ignored.");
            return Optional.empty();
        }
        return Optional.of(reference);
    }

    // ------------------------------------------------------------------------ internals

    /** Lowercase hex HMAC-SHA512 of the raw body under the platform secret. */
    private String hexSignature(byte[] rawBody) {
        try {
            // Mac is not thread safe, and webhooks arrive concurrently. A fresh instance per call.
            Mac mac = Mac.getInstance(SIGNATURE_ALGORITHM);
            mac.init(new SecretKeySpec(signingKey, SIGNATURE_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(rawBody));

        } catch (GeneralSecurityException impossible) {
            // HmacSHA512 is required of every JVM, so this cannot happen for a non-empty key -- and if
            // it somehow did, the request must fail rather than be treated as verified.
            throw new PaymentProviderException("Could not compute a callback signature.", impossible);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }
}
