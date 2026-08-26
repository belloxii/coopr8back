package com.invo.coopr8.payment;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.RestTemplate;

import com.invo.coopr8.model.Role;
import com.invo.coopr8.payment.paystack.PaystackRestConfig;
import com.invo.coopr8.security.AuthPrincipal;
import com.invo.coopr8.support.AbstractTwoTenantTest;
import com.invo.coopr8.support.TenantFixture.Tenant;
import com.invo.coopr8.tenant.ActiveTenant;
import com.invo.coopr8.tenant.TenantContext;

/**
 * Base class for the Stage 0 payment tests: two cooperatives, and a Paystack that is a test double.
 *
 * <h2>No test ever reaches Paystack</h2>
 * {@link MockRestServiceServer} is bound to the {@code paystackRestTemplate} bean before every
 * test, which replaces that template's request factory. From then on every call
 * {@code PaystackApiClient} makes is answered by an expectation the test declared -- and a call
 * <em>no</em> expectation covers fails at the moment it is made rather than travelling to
 * {@code api.paystack.co}. That is why the double is installed here unconditionally rather than in
 * the tests that happen to need it: a payment test that forgot to stub something must fail, not
 * transact against a live payment provider.
 *
 * <p>Note that such a failure surfaces as an {@link AssertionError}, which
 * {@code PaystackApiClient}'s {@code catch (RestClientException)} does not swallow. An unstubbed
 * provider call therefore breaks the test loudly instead of being converted into a tidy
 * "provider unavailable" refusal.
 *
 * <h2>The signing key is the configured one</h2>
 * {@link #signatureFor} reads {@code paystack.secret.key} from the test profile rather than
 * hardcoding it, so a valid signature in a test is valid for the same reason a real one is: it is
 * an HMAC under the key the application is actually configured with. A test that hardcoded the
 * secret would keep passing if the application stopped reading configuration altogether.
 *
 * <h2>Fixture values these tests depend on</h2>
 * {@code TenantFixture} gives each cooperative a {@code SELF_PAY} member whose {@code saving_plan}
 * is ₦5,000.00, and an administrator who is {@code GOVERNMENT} and so cannot pay online at all.
 * Savings payments must equal the plan exactly, which makes ₦5,000.00 -- 500,000 kobo -- the only
 * savings amount these tests can use.
 */
public abstract class AbstractPaymentTest extends AbstractTwoTenantTest {

    // ------------------------------------------------------------------ Paystack's endpoints
    // Paystack's contract restated from the outside. Duplicated from PaystackApiClient on purpose:
    // if the client's paths or query shape change, these tests must fail rather than follow along.

    protected static final String PAYSTACK_BASE = "https://api.paystack.co";
    protected static final String INITIALIZE_URL = PAYSTACK_BASE + "/transaction/initialize";
    protected static final String VERIFY_URL = PAYSTACK_BASE + "/transaction/verify/";
    protected static final String SUBACCOUNT_URL = PAYSTACK_BASE + "/subaccount";

    protected static final String SIGNATURE_HEADER = "x-paystack-signature";
    protected static final String WEBHOOK_PATH = "/api/webhook/paystack";
    protected static final String INITIALIZE_PATH = "/api/payments/initialize";

    private static final String HMAC_SHA512 = "HmacSHA512";

    // ------------------------------------------------------------------ fixture amounts

    /** The seeded member's monthly savings plan. A savings payment must match it exactly. */
    protected static final BigDecimal SAVINGS_PLAN = new BigDecimal("5000.00");

    /** {@link #SAVINGS_PLAN} in kobo, which is the unit the frontend sends and Paystack uses. */
    protected static final long SAVINGS_PLAN_KOBO = 500_000L;

    /** The seeded member's opening savings balance, also ₦5,000.00. */
    protected static final BigDecimal OPENING_SAVINGS_BALANCE = new BigDecimal("5000.00");

    // ------------------------------------------------------------------ collaborators

    @Autowired
    @Qualifier(PaystackRestConfig.REST_TEMPLATE)
    protected RestTemplate paystackRestTemplate;

    /** The platform's Paystack secret as the application resolved it, not as a test invented it. */
    @Value("${paystack.secret.key}")
    protected String paystackSecret;

    /** Paystack, as a test double. Rebound before every test. */
    protected MockRestServiceServer paystack;

    @BeforeEach
    void interceptEveryPaystackCall() {
        resetPaystackDouble();
    }

    /**
     * Installs a fresh Paystack double, discarding any expectations already declared or met.
     *
     * <p>Called before every test, and available mid-test: {@code MockRestServiceServer} refuses new
     * expectations once a request has been made, so a test that needs to say "and now nothing more
     * may be sent" has to start a new double rather than add to the old one.
     */
    protected void resetPaystackDouble() {
        // ignoreExpectOrder: a reconciliation search's pages and a verification call are
        // independent of one another, and pinning their order would make these tests fail for
        // reasons that have nothing to do with payment safety.
        paystack = MockRestServiceServer.bindTo(paystackRestTemplate)
                .ignoreExpectOrder(true)
                .build();
    }

    // ------------------------------------------------------------------ Paystack expectations

    /** Paystack confirms {@code reference} was paid, for however many verifications are made. */
    protected void paystackConfirms(String reference, long amountInKobo) {
        paystack.expect(manyTimes(), requestTo(VERIFY_URL + reference))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + paystackSecret))
                .andRespond(withSuccess("""
                        {"status":true,"message":"Verification successful",
                         "data":{"status":"success","reference":"%s","amount":%d,"currency":"NGN"}}
                        """.formatted(reference, amountInKobo), MediaType.APPLICATION_JSON));
    }

    /**
     * Asserts -- together with {@link MockRestServiceServer#verify()} -- that Paystack was not
     * contacted at all.
     *
     * <p>A bound server already fails on an unexpected call, so this is belt and braces; declaring
     * it makes the intent legible, which matters for the rejection tests where "nothing was asked,
     * nothing was read, nothing was moved" is the property under test.
     */
    protected void paystackMustNotBeContacted() {
        paystack.expect(never(), requestTo(startsWith(PAYSTACK_BASE)));
    }

    // ------------------------------------------------------------------ the callback

    /** A Paystack {@code charge.success} body, byte for byte as it would arrive. */
    protected static byte[] chargeSuccess(String reference, long amountInKobo) {
        return ("{\"event\":\"charge.success\",\"data\":{\"reference\":\"" + reference
                + "\",\"status\":\"success\",\"amount\":" + amountInKobo + "}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The signature Paystack would send for these exact bytes: lowercase-hex HMAC-SHA512 under the
     * configured secret, computed here independently of the code under test.
     */
    protected String signatureFor(byte[] rawBody) throws Exception {
        Mac mac = Mac.getInstance(HMAC_SHA512);
        mac.init(new SecretKeySpec(paystackSecret.getBytes(StandardCharsets.UTF_8), HMAC_SHA512));
        return HexFormat.of().formatHex(mac.doFinal(rawBody));
    }

    /** Posts a callback to the real endpoint, through the real filter chain. */
    protected ResultActions postCallback(byte[] rawBody, String signature) throws Exception {
        MockHttpServletRequestBuilder request = post(WEBHOOK_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(rawBody);

        if (signature != null) {
            request = request.header(SIGNATURE_HEADER, signature);
        }
        return mockMvc.perform(request);
    }

    /** Posts a callback signed correctly for its own body. */
    protected ResultActions postSignedCallback(byte[] rawBody) throws Exception {
        return postCallback(rawBody, signatureFor(rawBody));
    }

    // ------------------------------------------------------------------ seeding

    /**
     * Connects a cooperative to a provider account, as a completed setup would have left it.
     *
     * <p>Written in SQL rather than through {@code TenantPaymentSetupService} so the routing and
     * idempotency tests do not depend on the setup tests passing.
     */
    protected void connectPaymentAccount(Tenant tenant, String providerAccountReference) {
        jdbcTemplate.update("""
                INSERT INTO organization_payment_config
                    (organization_id, provider, account_mode, provider_account_reference, status,
                     settlement_bank_code, settlement_account_number, settlement_account_name,
                     created_at, updated_at)
                VALUES (?, 'PAYSTACK', 'PLATFORM_SUBACCOUNT', ?, 'ACTIVE', '058', ?, ?,
                        now(), now())
                """,
                tenant.organizationId(), providerAccountReference,
                settlementAccountNumber(tenant), tenant.slug() + " Cooperative Society");
    }

    /**
     * A setup that recorded its settlement destination but never got a provider reference back --
     * the interrupted state reconciliation exists to recover from.
     */
    protected void pendingPaymentSetup(Tenant tenant, String bankCode, String accountNumber) {
        jdbcTemplate.update("""
                INSERT INTO organization_payment_config
                    (organization_id, provider, account_mode, status,
                     settlement_bank_code, settlement_account_number, settlement_account_name,
                     created_at, updated_at)
                VALUES (?, 'PAYSTACK', 'PLATFORM_SUBACCOUNT', 'PENDING', ?, ?, ?, now(), now())
                """,
                tenant.organizationId(), bankCode, accountNumber,
                tenant.slug() + " Cooperative Society");
    }

    /** A distinct settlement account number per cooperative, stable across runs. */
    protected static String settlementAccountNumber(Tenant tenant) {
        return ALPHA_SLUG.equals(tenant.slug()) ? "0123456789" : "9876543210";
    }

    /**
     * A payment COOPR8 has recorded and not yet credited -- the state {@code reserve} commits
     * before the provider is contacted, and the state a callback arrives into.
     *
     * @return the payment's id
     */
    protected long reservedPayment(Tenant tenant, long userId, String reference,
            String purpose, Long targetId, BigDecimal amount) {

        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO payment_transaction
                    (organization_id, user_id, provider, provider_reference, purpose, target_id,
                     amount, status, created_at, updated_at)
                VALUES (?, ?, 'PAYSTACK', ?, ?, ?, ?, 'PENDING', now(), now())
                RETURNING id
                """, Long.class,
                tenant.organizationId(), userId, reference, purpose, targetId, amount);

        if (id == null) {
            throw new IllegalStateException("Could not reserve a payment for the test.");
        }
        return id;
    }

    /** A reserved savings payment for the cooperative's seeded member, at their plan amount. */
    protected long reservedSavingsPayment(Tenant tenant, String reference) {
        return reservedPayment(tenant, tenant.member().id(), reference, "SAVINGS", null,
                SAVINGS_PLAN);
    }

    // ------------------------------------------------------------------ committed-state reads
    // Straight SQL, bypassing every application filter: what the database actually holds, not what
    // a repository would be willing to show the current tenant.

    protected String paymentStatus(String reference) {
        return single("SELECT status FROM payment_transaction WHERE provider_reference = ?",
                String.class, reference);
    }

    protected LocalDateTime paymentProcessedAt(String reference) {
        return single("SELECT processed_at FROM payment_transaction WHERE provider_reference = ?",
                LocalDateTime.class, reference);
    }

    protected Long paymentOrganizationId(String reference) {
        return single("SELECT organization_id FROM payment_transaction WHERE provider_reference = ?",
                Long.class, reference);
    }

    protected BigDecimal savingsBalance(long userId) {
        return single("SELECT savings_balance FROM users WHERE id = ?", BigDecimal.class, userId);
    }

    protected BigDecimal sharesBalance(long userId) {
        return single("SELECT shares_balance FROM users WHERE id = ?", BigDecimal.class, userId);
    }

    protected String paymentConfigStatus(long organizationId) {
        return single("SELECT status FROM organization_payment_config WHERE organization_id = ?",
                String.class, organizationId);
    }

    protected String paymentConfigReference(long organizationId) {
        return single("""
                SELECT provider_account_reference FROM organization_payment_config
                 WHERE organization_id = ?
                """, String.class, organizationId);
    }

    protected String paymentConfigAccountNumber(long organizationId) {
        return single("""
                SELECT settlement_account_number FROM organization_payment_config
                 WHERE organization_id = ?
                """, String.class, organizationId);
    }

    /** One column of at most one row, or {@code null} -- an absent row is an answer, not a throw. */
    protected <T> T single(String sql, Class<T> type, Object... arguments) {
        return jdbcTemplate.query(sql,
                resultSet -> resultSet.next() ? resultSet.getObject(1, type) : null,
                arguments);
    }

    // ------------------------------------------------------------------ acting as somebody

    /**
     * Puts a verified administrator in place without an HTTP request.
     *
     * <p>Needed because {@code TenantPaymentSetupService} has no endpoint at this stage -- the
     * administrator API belongs to Stage 2 -- and it takes its tenant from
     * {@code CurrentAuth.requireAdmin()}. Both the Spring security context and
     * {@link TenantContext} are set, which is exactly the pair {@code JwtTokenValidator} binds for
     * a real request, so the service runs against the same ambient state production gives it.
     */
    protected void actingAsAdminOf(Tenant tenant) {
        AuthPrincipal principal = new AuthPrincipal(
                tenant.admin().id(),
                tenant.organizationId(),
                tenant.slug(),
                tenant.admin().ledgerID(),
                Set.of(Role.ROLE_ADMIN.name()),
                "test-token-" + tenant.slug());

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.authorities()));
        TenantContext.bind(new ActiveTenant(tenant.organizationId(), tenant.slug()));
    }

    /** Drops the ambient caller, so no assertion can pass because of a leftover identity. */
    protected void stopActing() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }
}
