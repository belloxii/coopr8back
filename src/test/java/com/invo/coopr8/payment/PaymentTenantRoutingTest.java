package com.invo.coopr8.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Where the money lands is decided by the token, and by nothing else.
 *
 * <h2>The threat</h2>
 * Every cooperative on COOPR8 shares one Paystack merchant account; each is settled through its own
 * subaccount. So the {@code subaccount} field on the outgoing initialize call <em>is</em> the answer
 * to "whose money is this". If a browser could influence that field -- by sending an
 * {@code organizationId}, a {@code subaccount}, or anything else -- then one cooperative's members
 * could be made to fund another cooperative, or a member could route their own contribution to an
 * account they control. That is not a leak of data; it is a redirection of funds.
 *
 * <h2>The design these tests hold in place</h2>
 * {@code PaymentInitializationRequest} has no field for a destination. The destination is read from
 * {@code organization_payment_config} for the organization the JWT resolved to, via
 * {@code findByOrganizationId(organization.getId())}, where {@code organization} came from the
 * authenticated user. The tests therefore assert on the <em>outgoing</em> request to Paystack: that
 * is the only place the decision is observable, and asserting the HTTP response instead would prove
 * nothing about it.
 *
 * <p>Both cooperatives are connected to distinct subaccounts, so a routing bug shows up as the wrong
 * value rather than as an absent one.
 */
class PaymentTenantRoutingTest extends AbstractPaymentTest {

    private static final String ALPHA_SUBACCOUNT = "ACCT_alpha_settlement";
    private static final String BETA_SUBACCOUNT = "ACCT_beta_settlement";

    private static final String NOT_SET_UP_MESSAGE =
            "This cooperative has not finished setting up online payments. "
                    + "Please contact your cooperative's administrator.";

    /** The body COOPR8 sent to Paystack, captured as it went past. Null until a checkout is made. */
    private final AtomicReference<String> outgoingCheckout = new AtomicReference<>();

    @Test
    @DisplayName("Alpha's member is settled into Alpha's subaccount")
    void alphaSettlesIntoAlphaSubaccount() throws Exception {
        connectPaymentAccount(alpha, ALPHA_SUBACCOUNT);
        connectPaymentAccount(beta, BETA_SUBACCOUNT);
        expectCheckoutFor(ALPHA_SUBACCOUNT);

        startSavingsPayment(alphaMemberToken).andExpect(status().isOk());

        paystack.verify();
        assertThat(sentSubaccount())
                .as("Alpha's contribution must be split to Alpha's settlement account")
                .isEqualTo(ALPHA_SUBACCOUNT);
        assertThat(sentCooperative())
                .as("and the cooperative COOPR8 names to Paystack is Alpha")
                .isEqualTo(ALPHA_SLUG);
        assertThat(paymentOrganizationId(sentReference()))
                .as("the payment record COOPR8 keeps belongs to Alpha")
                .isEqualTo(alpha.organizationId());
    }

    @Test
    @DisplayName("Beta's member is settled into Beta's subaccount")
    void betaSettlesIntoBetaSubaccount() throws Exception {
        connectPaymentAccount(alpha, ALPHA_SUBACCOUNT);
        connectPaymentAccount(beta, BETA_SUBACCOUNT);
        expectCheckoutFor(BETA_SUBACCOUNT);

        startSavingsPayment(betaMemberToken).andExpect(status().isOk());

        paystack.verify();
        assertThat(sentSubaccount())
                .as("the same request shape, from a different cooperative's member, must settle "
                        + "somewhere else entirely")
                .isEqualTo(BETA_SUBACCOUNT);
        assertThat(sentCooperative()).isEqualTo(BETA_SLUG);
        assertThat(paymentOrganizationId(sentReference())).isEqualTo(beta.organizationId());
    }

    /**
     * The attack itself: Alpha's member asks, in as many words, to be settled into Beta's account.
     *
     * <p>The request carries {@code organizationId}, {@code subaccount} and {@code provider} both at
     * the top level and inside {@code metadata}. Nothing binds them: the DTO has no such fields, and
     * {@code providerMetadata} builds the outgoing metadata from the resolved organization rather
     * than forwarding what the caller sent. The test asserts that second part too, because metadata
     * that echoed a caller-supplied {@code subaccount} would be a plausible future mistake -- and on
     * some providers metadata is not inert.
     */
    @Test
    @DisplayName("Alpha cannot submit Beta's provider account, however it is dressed up")
    void alphaCannotSubmitBetasProviderAccount() throws Exception {
        connectPaymentAccount(alpha, ALPHA_SUBACCOUNT);
        connectPaymentAccount(beta, BETA_SUBACCOUNT);
        expectCheckoutFor(ALPHA_SUBACCOUNT);

        Map<String, Object> forgedMetadata = new LinkedHashMap<>();
        forgedMetadata.put("type", "savings");
        forgedMetadata.put("subaccount", BETA_SUBACCOUNT);
        forgedMetadata.put("organizationId", beta.organizationId());

        Map<String, Object> forgedRequest = new LinkedHashMap<>();
        forgedRequest.put("amount", SAVINGS_PLAN_KOBO);
        forgedRequest.put("callback_url", "https://alpha-coop.example/paid");
        forgedRequest.put("subaccount", BETA_SUBACCOUNT);
        forgedRequest.put("organizationId", beta.organizationId());
        forgedRequest.put("provider", "FLUTTERWAVE");
        forgedRequest.put("metadata", forgedMetadata);

        // Accepted, not rejected: the forged fields are not refused, they are simply not consulted.
        // A 400 here would be the weaker guarantee, because it would depend on COOPR8 recognising
        // every field name an attacker might try.
        as(alphaMemberToken, post(INITIALIZE_PATH), forgedRequest).andExpect(status().isOk());

        paystack.verify();
        assertThat(sentSubaccount())
                .as("the destination came from Alpha's configuration row, not from the request")
                .isEqualTo(ALPHA_SUBACCOUNT);

        JsonNode metadata = sentBody().path("metadata");
        assertThat(metadata.has("subaccount"))
                .as("caller metadata must not be forwarded to the provider at all")
                .isFalse();
        assertThat(metadata.has("organizationId"))
                .as("caller metadata must not be forwarded to the provider at all")
                .isFalse();
        assertThat(metadata.path("cooperative").asText()).isEqualTo(ALPHA_SLUG);
        assertThat(metadata.path("purpose").asText()).isEqualTo("savings");

        assertThat(paymentOrganizationId(sentReference()))
                .as("and the payment is recorded against Alpha, whatever organizationId was sent")
                .isEqualTo(alpha.organizationId());
        assertThat(countWhere("payment_transaction WHERE organization_id = ?",
                beta.organizationId()))
                .as("Beta gained no payment record from Alpha's request")
                .isZero();
    }

    /**
     * A cooperative that has not finished payment setup cannot take money.
     *
     * <p>Fails closed, and fails <em>early</em>: refused before a reference is minted, before a row
     * is written and before Paystack is contacted, so an unconfigured cooperative accumulates
     * neither orphan payment records nor half-started checkouts.
     */
    @Test
    @DisplayName("a cooperative with no payment configuration is refused, not defaulted")
    void missingConfigurationFailsSafely() throws Exception {
        // Deliberately no configuration for either cooperative.
        paystackMustNotBeContacted();

        startSavingsPayment(alphaMemberToken)
                .andExpect(status().isBadRequest())
                .andExpect(MockMvcResultMatchers.jsonPath("$.status").value(false))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value(NOT_SET_UP_MESSAGE))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data").doesNotExist());

        assertThat(countWhere("payment_transaction"))
                .as("a refused initialization must leave no payment record behind")
                .isZero();
        paystack.verify();
    }

    /**
     * Initialization picks the authenticated tenant's configuration, and will not fall back to
     * somebody else's.
     *
     * <p>The state here is the one a naive implementation gets wrong: exactly one cooperative on the
     * platform is set up for payments, and it is not the one asking. A lookup that fetched "the
     * payment configuration" rather than "this organization's payment configuration" -- a
     * {@code findAll().get(0)}, a {@code findFirstBy...}, a cached singleton -- would pass every
     * other test in this class while quietly settling Alpha's savings into Beta's bank account.
     *
     * <p>Note what the Paystack double is primed to do: it will happily accept a checkout into
     * Beta's subaccount. So the test does not rest on a stub refusing the wrong call. Alpha is
     * refused by COOPR8 while the wrong destination is standing there available, and the expectation
     * is then consumed by Beta's own member -- which is also what proves the refusal was about
     * Alpha rather than about the platform being misconfigured.
     */
    @Test
    @DisplayName("initialization uses the authenticated tenant's configuration, never another's")
    void initializationSelectsTheAuthenticatedTenantsConfiguration() throws Exception {
        connectPaymentAccount(beta, BETA_SUBACCOUNT);
        expectCheckoutFor(BETA_SUBACCOUNT);

        assertThat(countWhere("organization_payment_config"))
                .as("the premise: Beta's is the only payment configuration that exists")
                .isEqualTo(1L);

        startSavingsPayment(alphaMemberToken)
                .andExpect(status().isBadRequest())
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value(NOT_SET_UP_MESSAGE));

        assertThat(outgoingCheckout.get())
                .as("Alpha's request must not have produced a checkout at all -- not one into "
                        + "Beta's subaccount, not one into any subaccount")
                .isNull();
        assertThat(countWhere("payment_transaction")).isZero();

        // The converse: with the same single configuration in place, its owner can pay.
        startSavingsPayment(betaMemberToken).andExpect(status().isOk());

        paystack.verify();
        assertThat(sentSubaccount()).isEqualTo(BETA_SUBACCOUNT);
        assertThat(paymentOrganizationId(sentReference())).isEqualTo(beta.organizationId());
    }

    // ------------------------------------------------------------------ helpers

    /** A savings payment for the plan amount -- the plainest possible request. */
    private ResultActions startSavingsPayment(String token) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", SAVINGS_PLAN_KOBO);
        body.put("callback_url", "https://coopr8.example/paid");
        body.put("metadata", Map.of("type", "savings"));

        return as(token, post(INITIALIZE_PATH), body);
    }

    /**
     * Expects one checkout to be created, with {@code expectedSubaccount} as its destination.
     *
     * <p>The {@code jsonPath} expectation fails at the moment of the call rather than afterwards,
     * which localises a routing bug to the request that caused it. The response echoes back
     * whatever reference COOPR8 minted, as Paystack does -- {@code PaystackApiClient} refuses a
     * reference that disagrees, so a stub returning a fixed one would fail for the wrong reason.
     */
    private void expectCheckoutFor(String expectedSubaccount) {
        paystack.expect(once(), requestTo(INITIALIZE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + paystackSecret))
                .andExpect(jsonPath("$.subaccount").value(expectedSubaccount))
                .andExpect(jsonPath("$.amount").value((int) SAVINGS_PLAN_KOBO))
                .andRespond(request -> {
                    String body = ((MockClientHttpRequest) request).getBodyAsString();
                    outgoingCheckout.set(body);

                    String reference = objectMapper.readTree(body).path("reference").asText();
                    return withSuccess("""
                            {"status":true,"message":"Authorization URL created",
                             "data":{"authorization_url":"https://checkout.paystack.com/%s",
                                     "access_code":"AC-%s","reference":"%s"}}
                            """.formatted(reference, reference, reference),
                            MediaType.APPLICATION_JSON)
                            .createResponse(request);
                });
    }

    private JsonNode sentBody() throws Exception {
        String body = outgoingCheckout.get();
        assertThat(body).as("COOPR8 must actually have contacted the provider").isNotNull();
        return objectMapper.readTree(body);
    }

    private String sentSubaccount() throws Exception {
        return sentBody().path("subaccount").asText(null);
    }

    private String sentCooperative() throws Exception {
        return sentBody().path("metadata").path("cooperative").asText(null);
    }

    private String sentReference() throws Exception {
        return sentBody().path("reference").asText(null);
    }
}
