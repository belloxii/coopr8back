package com.invo.coopr8.payment.paystack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.invo.coopr8.model.PaymentProviderName;
import com.invo.coopr8.payment.PaymentProviderException;
import com.invo.coopr8.payment.ProviderAccountRequest;
import com.invo.coopr8.payment.ProviderAccountResult;
import com.invo.coopr8.payment.ProviderCheckoutRequest;
import com.invo.coopr8.payment.ProviderCheckoutResult;
import com.invo.coopr8.payment.ProviderVerificationResult;

import lombok.extern.slf4j.Slf4j;

/**
 * The only class in COOPR8 that knows Paystack's HTTP contract.
 *
 * <p><strong>Every Paystack URL, field name and unit convention is in this file, on purpose.</strong>
 * The contract below is written from Paystack's published REST API. It could not be re-verified
 * against an authoritative source from the environment this was written in -- {@code paystack.com/docs}
 * answers 403 to a programmatic fetch -- so it is gathered here, named, and commented, so that a
 * reviewer with the documentation open can check it in one place instead of hunting field names
 * through a service layer. Nothing outside this package sees any of it.
 *
 * <h2>The contract this encodes</h2>
 * <ul>
 *   <li>Base {@code https://api.paystack.co}; bearer auth with the platform secret key.</li>
 *   <li>Every response is {@code {status: boolean, message: string, data: ...}}, and
 *       {@code status: false} is a refusal even under HTTP 200.</li>
 *   <li>{@code POST /transaction/initialize} -- {@code email}, {@code amount} (<strong>kobo</strong>,
 *       integer), {@code reference} (merchant-supplied), {@code callback_url}, {@code subaccount}
 *       ({@code ACCT_} code, the split destination), {@code metadata}. Returns
 *       {@code data.authorization_url}, {@code data.access_code}, {@code data.reference}.</li>
 *   <li>{@code GET /transaction/verify/{reference}} -- returns {@code data.status} (the string
 *       {@code "success"} when the money was taken) and {@code data.amount} in kobo.</li>
 *   <li>{@code POST /subaccount} -- {@code business_name}, {@code settlement_bank} (bank
 *       <em>code</em>), {@code account_number}, {@code percentage_charge}, {@code primary_contact_email},
 *       {@code description}. Returns {@code data.subaccount_code} ({@code ACCT_xxxxxxxx}) and
 *       {@code data.active}.</li>
 *   <li>{@code GET /subaccount?perPage=&page=} -- returns {@code data} as an array of subaccounts,
 *       each with {@code subaccount_code}, {@code account_number} and {@code settlement_bank}.</li>
 * </ul>
 *
 * <h2>Amounts</h2>
 * The domain speaks naira. Paystack speaks kobo. The conversion happens here and nowhere else.
 */
@Slf4j
@Component
public class PaystackApiClient {

    // ------------------------------------------------------------------ the contract

    private static final String INITIALIZE_PATH = "/transaction/initialize";
    private static final String VERIFY_PATH = "/transaction/verify/";
    private static final String SUBACCOUNT_PATH = "/subaccount";

    /** Paystack transacts in kobo; COOPR8's domain is in naira. */
    private static final BigDecimal MINOR_UNITS_PER_MAJOR = BigDecimal.valueOf(100);

    /** The value of {@code data.status} that means the money was actually taken. */
    private static final String TRANSACTION_SUCCESS = "success";

    /** How many subaccounts to read per page when reconciling an interrupted setup. */
    private static final int LOOKUP_PAGE_SIZE = 100;

    /**
     * How many pages of subaccounts the reconciliation search will read before giving up and
     * answering "indeterminate".
     *
     * <p>Bounded because an unbounded loop against a provider is a way to hang a request, and honest
     * about it because the alternative -- treating "I stopped looking" as "it is not there" -- is
     * what creates a second account holding a cooperative's money. Ten pages is 1,000 subaccounts.
     */
    private static final int LOOKUP_PAGE_LIMIT = 10;

    // ------------------------------------------------------------------ collaborators

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String secretKey;
    private final BigDecimal platformPercentageCharge;

    public PaystackApiClient(
            @Qualifier(PaystackRestConfig.REST_TEMPLATE) RestTemplate restTemplate,
            @Value("${paystack.api.base-url:https://api.paystack.co}") String baseUrl,
            @Value("${paystack.secret.key}") String secretKey,
            @Value("${coopr8.payments.paystack.platform-percentage-charge:#{null}}")
            BigDecimal platformPercentageCharge) {

        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.secretKey = secretKey;
        this.platformPercentageCharge = platformPercentageCharge;
    }

    // ------------------------------------------------------------------ taking a payment

    /** Starts a Paystack checkout for a reference COOPR8 has already recorded. */
    public ProviderCheckoutResult initializeTransaction(ProviderCheckoutRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", request.payerEmail());
        body.put("amount", toKobo(request.amount()));
        body.put("reference", request.providerReference());

        if (request.callbackUrl() != null && !request.callbackUrl().isBlank()) {
            body.put("callback_url", request.callbackUrl());
        }

        // The split destination. Resolved from the paying member's own cooperative by the caller;
        // there is no code path by which a browser reaches this field.
        if (request.settlementAccountReference() != null
                && !request.settlementAccountReference().isBlank()) {
            body.put("subaccount", request.settlementAccountReference());
        }

        if (request.metadata() != null && !request.metadata().isEmpty()) {
            body.put("metadata", request.metadata());
        }

        JsonNode data = requireData(post(INITIALIZE_PATH, body), "initialize a transaction");

        String authorizationUrl = text(data, "authorization_url");
        if (authorizationUrl == null) {
            throw new PaymentProviderException(
                    "Paystack accepted the transaction but returned no authorization URL.");
        }

        // Paystack echoes the merchant reference. If it ever disagreed, the payment COOPR8 recorded
        // and the payment Paystack is taking would be different payments, and the callback would
        // name one COOPR8 has no row for.
        String echoed = text(data, "reference");
        if (echoed != null && !echoed.equals(request.providerReference())) {
            throw new PaymentProviderException(
                    "Paystack returned a different reference than the one supplied; refusing to "
                            + "start a payment COOPR8 cannot recognise on callback.");
        }

        return new ProviderCheckoutResult(
                request.providerReference(), authorizationUrl, text(data, "access_code"));
    }

    /** Asks Paystack, over an authenticated call, what happened to a payment. */
    public ProviderVerificationResult verifyTransaction(String providerReference) {
        JsonNode envelope = get(VERIFY_PATH + encodePathSegment(providerReference));

        // A refusal here is not an error: an unknown reference legitimately verifies as "no".
        if (!isSuccessEnvelope(envelope)) {
            return ProviderVerificationResult.unpaid(providerReference);
        }

        JsonNode data = envelope.path("data");
        if (!data.isObject()) {
            return ProviderVerificationResult.unpaid(providerReference);
        }

        boolean paid = TRANSACTION_SUCCESS.equalsIgnoreCase(text(data, "status"));
        if (!paid) {
            return ProviderVerificationResult.unpaid(providerReference);
        }

        return new ProviderVerificationResult(providerReference, true, toNaira(data.path("amount")));
    }

    // ------------------------------------------------------- the cooperative's own account

    /** Creates a Paystack subaccount settling to a cooperative's own bank account. */
    public ProviderAccountResult createSubaccount(ProviderAccountRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("business_name", request.businessName());
        body.put("settlement_bank", request.bankCode());
        body.put("account_number", request.accountNumber());
        body.put("percentage_charge", requirePlatformPercentageCharge());

        if (request.contactEmail() != null && !request.contactEmail().isBlank()) {
            body.put("primary_contact_email", request.contactEmail());
        }

        JsonNode data = requireData(post(SUBACCOUNT_PATH, body), "create a subaccount");

        String subaccountCode = text(data, "subaccount_code");
        if (subaccountCode == null || subaccountCode.isBlank()) {
            throw new PaymentProviderException(
                    "Paystack reported success but returned no subaccount code.");
        }

        // Absent 'active' is read as active: Paystack returns a usable subaccount on success, and
        // treating a missing flag as unusable would refuse an account that had just been created.
        boolean active = !data.path("active").isBoolean() || data.path("active").asBoolean();

        return new ProviderAccountResult(PaymentProviderName.PAYSTACK, subaccountCode, active);
    }

    /**
     * Every subaccount Paystack holds whose settlement account number matches, or an empty result
     * when the enumeration could not be completed.
     *
     * @return the matching subaccount codes, and {@code null} -- distinct from an empty list -- when
     *         the search was cut short and therefore proves nothing
     */
    public List<String> findSubaccountCodesByAccountNumber(String accountNumber) {
        List<String> matches = new ArrayList<>();

        for (int page = 1; page <= LOOKUP_PAGE_LIMIT; page++) {
            JsonNode envelope;
            try {
                envelope = get(UriComponentsBuilder.fromPath(SUBACCOUNT_PATH)
                        .queryParam("perPage", LOOKUP_PAGE_SIZE)
                        .queryParam("page", page)
                        .toUriString());
            } catch (PaymentProviderException unreachable) {
                // Cannot enumerate, so cannot conclude. Reported as "do not know", never as "none".
                log.warn("Could not enumerate Paystack subaccounts for reconciliation (page {}).",
                        page);
                return null;
            }

            JsonNode data = envelope.path("data");
            if (!isSuccessEnvelope(envelope) || !data.isArray()) {
                return null;
            }

            for (JsonNode subaccount : data) {
                if (accountNumber.equals(text(subaccount, "account_number"))) {
                    String code = text(subaccount, "subaccount_code");
                    if (code != null) {
                        matches.add(code);
                    }
                }
            }

            // A short page is the last page, so the enumeration is complete and its answer -- even
            // an empty one -- can be relied on.
            if (data.size() < LOOKUP_PAGE_SIZE) {
                return matches;
            }
        }

        log.warn("Paystack holds more subaccounts than reconciliation will read ({} pages); "
                + "treating the search as inconclusive.", LOOKUP_PAGE_LIMIT);
        return null;
    }

    // ------------------------------------------------------------------------ mechanics

    private JsonNode post(String path, Map<String, Object> body) {
        HttpHeaders headers = authenticatedHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        return exchange(HttpMethod.POST, path, new HttpEntity<>(body, headers));
    }

    private JsonNode get(String path) {
        return exchange(HttpMethod.GET, path, new HttpEntity<>(authenticatedHeaders()));
    }

    private JsonNode exchange(HttpMethod method, String path, HttpEntity<?> entity) {
        try {
            ResponseEntity<JsonNode> response =
                    restTemplate.exchange(baseUrl + path, method, entity, JsonNode.class);

            JsonNode envelope = response.getBody();
            if (envelope == null) {
                throw new PaymentProviderException(
                        "Paystack returned an empty body for " + method + " " + path);
            }
            return envelope;

        } catch (RestClientException transportOrStatusFailure) {
            // Deliberately does not include the response body: Paystack error bodies echo request
            // content, and this message reaches logs. The exception type is enough to act on, and an
            // unreachable provider must fail rather than resolve to a default.
            throw new PaymentProviderException(
                    "Paystack call failed: " + method + " " + path, transportOrStatusFailure);
        }
    }

    private HttpHeaders authenticatedHeaders() {
        HttpHeaders headers = new HttpHeaders();
        // The platform's secret, from configuration. Never logged, never returned, never stored
        // against a tenant.
        headers.setBearerAuth(secretKey);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    /**
     * The {@code data} of an envelope that reports success.
     *
     * <p>Paystack answers a refusal with HTTP 200 and {@code status: false}, so the envelope has to
     * be read as well as the status code.
     */
    private JsonNode requireData(JsonNode envelope, String attempt) {
        if (!isSuccessEnvelope(envelope)) {
            throw new PaymentProviderException("Paystack refused to " + attempt + ".");
        }

        JsonNode data = envelope.path("data");
        if (!data.isObject()) {
            throw new PaymentProviderException(
                    "Paystack reported success for " + attempt + " but returned no data object.");
        }
        return data;
    }

    private static boolean isSuccessEnvelope(JsonNode envelope) {
        return envelope != null && envelope.path("status").asBoolean(false);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    /** Naira to kobo, as the integer Paystack expects. */
    private static long toKobo(BigDecimal naira) {
        return naira.multiply(MINOR_UNITS_PER_MAJOR)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** Kobo to naira at the platform's two-decimal money scale. */
    private static BigDecimal toNaira(JsonNode kobo) {
        if (!kobo.isNumber()) {
            return null;
        }
        return kobo.decimalValue()
                .divide(MINOR_UNITS_PER_MAJOR)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static String encodePathSegment(String segment) {
        return UriComponentsBuilder.fromPath("/{segment}")
                .buildAndExpand(segment)
                .encode()
                .toUriString()
                .substring(1);
    }

    /**
     * COOPR8's commission on a cooperative's transactions, as Paystack's {@code percentage_charge}.
     *
     * <p><strong>There is deliberately no default.</strong> This number decides how a cooperative's
     * members' money is divided, and both plausible readings of Paystack's field -- the platform's cut,
     * or the cooperative's share -- make one of {@code 0} and {@code 100} badly wrong. A default would
     * mean a deployment that never thought about it still routes money somewhere, which is the worst
     * of the available outcomes. So an unset value refuses to create the subaccount, with a message
     * saying what to set.
     */
    private BigDecimal requirePlatformPercentageCharge() {
        if (platformPercentageCharge == null) {
            throw new PaymentProviderException(
                    "coopr8.payments.paystack.platform-percentage-charge is not configured. "
                            + "COOPR8 will not create a settlement account without being told how "
                            + "the transaction is divided.");
        }
        return platformPercentageCharge;
    }
}
