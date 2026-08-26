package com.invo.coopr8.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.invo.coopr8.model.PaymentAccountMode;
import com.invo.coopr8.model.PaymentConfigStatus;
import com.invo.coopr8.model.PaymentProviderName;

/**
 * A cooperative gets its own settlement account on the platform's Paystack merchant account, created
 * for it, without anybody pasting a subaccount code into a form.
 *
 * <h2>Why this is a security test and not just a feature test</h2>
 * The subaccount code is the destination of every naira the cooperative's members pay. Whoever
 * controls how it is set controls where the money goes. So the operation deliberately has no
 * parameter naming a cooperative: it reads the tenant from {@code CurrentAuth.requireAdmin()} and the
 * organization from {@code organizationService.currentOrganizationEntity()}. An administrator can
 * only ever set up their own cooperative, because there is no way to express anything else --
 * {@link #anotherTenantsConfigurationCannotBeModified()} asserts that at the level of the API's
 * shape, of its behaviour, and of the database.
 *
 * <h2>Create is the last resort, not the first move</h2>
 * Setup searches the provider for an account already settling to the submitted bank account before
 * creating one, because the failure it is guarding against is not "no account" but "two accounts": a
 * first attempt that created a subaccount and then lost the response would, on retry, create a
 * second, and a cooperative with two settlement accounts has money arriving somewhere nobody is
 * reconciling. When the search cannot reach a conclusion, setup refuses rather than creating.
 *
 * <h2>No endpoint yet</h2>
 * The administrator API for payment configuration belongs to Stage 2, so these tests drive
 * {@code TenantPaymentSetupService} directly, with the ambient identity
 * {@code JwtTokenValidator} would have bound. That is the same code an endpoint will call.
 */
class TenantSubaccountSetupTest extends AbstractPaymentTest {

    private static final String BANK_CODE = "058";
    private static final String ALPHA_ACCOUNT = "0123456789";
    private static final String ALPHA_ACCOUNT_NAME = "Alpha Cooperative Society";

    private static final String CREATED_SUBACCOUNT = "ACCT_alpha_created";
    private static final String BETA_EXISTING_SUBACCOUNT = "ACCT_beta_existing";

    /** The platform's cut, as {@code application-test.properties} sets it. */
    private static final BigDecimal PLATFORM_PERCENTAGE_CHARGE = new BigDecimal("1.5");

    private static final String LOOKUP_PAGE_ONE = SUBACCOUNT_URL + "?perPage=100&page=1";

    @Autowired
    private TenantPaymentSetupService setupService;

    /** The body COOPR8 sent when creating a subaccount. */
    private final AtomicReference<String> createRequest = new AtomicReference<>();

    @Test
    @DisplayName("valid settlement information creates a subaccount and connects the cooperative")
    void validSettlementInformationCreatesASubaccount() throws Exception {
        actingAsAdminOf(alpha);
        paystackHoldsNoSubaccounts();
        paystackCreatesSubaccount(CREATED_SUBACCOUNT, true);

        TenantPaymentAccountView connected = setupService.connectSettlementAccount(
                new TenantSettlementDetails(BANK_CODE, ALPHA_ACCOUNT, ALPHA_ACCOUNT_NAME));

        paystack.verify();

        assertThat(connected.provider()).isEqualTo(PaymentProviderName.PAYSTACK);
        assertThat(connected.accountMode()).isEqualTo(PaymentAccountMode.PLATFORM_SUBACCOUNT);
        assertThat(connected.status()).isEqualTo(PaymentConfigStatus.ACTIVE);
        assertThat(connected.providerAccountReference()).isEqualTo(CREATED_SUBACCOUNT);
        assertThat(connected.payable())
                .as("a connected cooperative can take payments; that is what connected means")
                .isTrue();

        // What COOPR8 asked Paystack for. The percentage charge is the one business number here, and
        // production has no default for it -- an unset value refuses to create the account rather
        // than creating one on a guessed split.
        JsonNode sent = objectMapper.readTree(createRequest.get());
        assertThat(sent.path("settlement_bank").asText()).isEqualTo(BANK_CODE);
        assertThat(sent.path("account_number").asText()).isEqualTo(ALPHA_ACCOUNT);
        assertThat(sent.path("business_name").asText())
                .as("the cooperative is identified to the provider by its own name")
                .isNotBlank();
        assertThat(sent.path("percentage_charge").decimalValue())
                .isEqualByComparingTo(PLATFORM_PERCENTAGE_CHARGE);

        assertThat(paymentConfigStatus(alpha.organizationId())).isEqualTo("ACTIVE");
        assertThat(paymentConfigReference(alpha.organizationId())).isEqualTo(CREATED_SUBACCOUNT);
        assertThat(paymentConfigAccountNumber(alpha.organizationId())).isEqualTo(ALPHA_ACCOUNT);
    }

    @Test
    @DisplayName("the provider reference is stored against the organization that asked for it")
    void referenceIsStoredAgainstTheCorrectOrganization() {
        actingAsAdminOf(alpha);
        paystackHoldsNoSubaccounts();
        paystackCreatesSubaccount(CREATED_SUBACCOUNT, true);

        setupService.connectSettlementAccount(
                new TenantSettlementDetails(BANK_CODE, ALPHA_ACCOUNT, ALPHA_ACCOUNT_NAME));

        paystack.verify();

        assertThat(countWhere("organization_payment_config WHERE organization_id = ? "
                + "AND provider_account_reference = ?",
                alpha.organizationId(), CREATED_SUBACCOUNT))
                .as("Alpha's row holds the reference Paystack returned")
                .isEqualTo(1L);
        assertThat(countWhere("organization_payment_config WHERE organization_id <> ?",
                alpha.organizationId()))
                .as("and no other cooperative acquired a configuration from Alpha's setup -- Beta "
                        + "exists and is untouched")
                .isZero();

        // The lookup a member's payment will use resolves to Alpha's row, from Alpha's id alone.
        assertThat(paymentConfigReference(alpha.organizationId())).isEqualTo(CREATED_SUBACCOUNT);
        assertThat(paymentConfigReference(beta.organizationId())).isNull();
    }

    /**
     * Running setup again does not create a second account.
     *
     * <p>An already-connected cooperative short-circuits before the provider is reached at all: the
     * second call is not "search, find, adopt" but "already done, nothing to do". That matters
     * because an administrator pressing a button twice, or two administrators pressing it at once,
     * must not turn into two settlement accounts -- and because a call that re-derived the
     * destination each time would be a way to move where settled money lands.
     */
    @Test
    @DisplayName("repeated setup neither duplicates the account nor re-contacts the provider")
    void repeatedSetupDoesNotDuplicate() {
        actingAsAdminOf(alpha);
        paystackHoldsNoSubaccounts();
        paystackCreatesSubaccount(CREATED_SUBACCOUNT, true);

        TenantSettlementDetails details =
                new TenantSettlementDetails(BANK_CODE, ALPHA_ACCOUNT, ALPHA_ACCOUNT_NAME);

        TenantPaymentAccountView first = setupService.connectSettlementAccount(details);
        paystack.verify();

        // A new double, primed to accept nothing: the second call must not talk to Paystack at all.
        resetPaystackDouble();
        paystackMustNotBeContacted();

        TenantPaymentAccountView second = setupService.connectSettlementAccount(details);

        paystack.verify();
        assertThat(second.providerAccountReference())
                .as("the same account, not a new one")
                .isEqualTo(first.providerAccountReference());
        assertThat(second.status()).isEqualTo(PaymentConfigStatus.ACTIVE);
        assertThat(countWhere("organization_payment_config WHERE organization_id = ?",
                alpha.organizationId()))
                .as("one configuration row per cooperative, enforced by "
                        + "uk_organization_payment_config_organization")
                .isEqualTo(1L);
    }

    /**
     * An interrupted first attempt is reconciled, not repeated.
     *
     * <p>The state: COOPR8 recorded its intent to settle to this bank account, the provider created
     * the subaccount, and the response never came back -- so the configuration is {@code PENDING}
     * with no reference while the account exists. Retrying must adopt what is there. Creating again
     * would leave two subaccounts on the same bank account, only one of which COOPR8 knows about.
     */
    @Test
    @DisplayName("an interrupted setup adopts the account the provider already holds")
    void interruptedSetupAdoptsTheExistingAccount() {
        pendingPaymentSetup(alpha, BANK_CODE, ALPHA_ACCOUNT);
        actingAsAdminOf(alpha);

        String orphaned = "ACCT_alpha_orphaned";
        paystackHoldsSubaccount(ALPHA_ACCOUNT, orphaned);
        // No create expectation is declared: reaching the create call fails this test.

        TenantPaymentAccountView reconciled = setupService.connectSettlementAccount(
                new TenantSettlementDetails(BANK_CODE, ALPHA_ACCOUNT, ALPHA_ACCOUNT_NAME));

        paystack.verify();
        assertThat(reconciled.providerAccountReference())
                .as("the existing account is adopted rather than a second one created")
                .isEqualTo(orphaned);
        assertThat(reconciled.status()).isEqualTo(PaymentConfigStatus.ACTIVE);
        assertThat(paymentConfigReference(alpha.organizationId())).isEqualTo(orphaned);
    }

    /**
     * When the provider will not create the account, the cooperative stays disconnected.
     *
     * <p>Paystack refusing with HTTP 200 and {@code "status": false} is its documented failure shape,
     * so that is what is stubbed here rather than a transport error.
     *
     * <p>The important part is not the 502. It is that {@code PENDING} with no reference is not
     * payable, so a member cannot start a payment against a cooperative whose setup failed -- and
     * that the next attempt will search before creating, because the recorded destination is still
     * there to search for.
     */
    @Test
    @DisplayName("a Paystack failure leaves the cooperative unconnected and unable to take payments")
    void paystackFailureDoesNotMarkTheTenantConnected() {
        actingAsAdminOf(alpha);
        paystackHoldsNoSubaccounts();
        paystack.expect(once(), requestTo(SUBACCOUNT_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"status\":false,\"message\":\"Invalid settlement bank\"}",
                        MediaType.APPLICATION_JSON));

        Throwable refusal = catchThrowable(() -> setupService.connectSettlementAccount(
                new TenantSettlementDetails(BANK_CODE, ALPHA_ACCOUNT, ALPHA_ACCOUNT_NAME)));

        paystack.verify();

        assertThat(refusal)
                .as("the provider's failure is reported as a provider failure, not as the "
                        + "administrator's mistake")
                .isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) refusal).getStatusCode())
                .isEqualTo(HttpStatus.BAD_GATEWAY);

        assertThat(paymentConfigStatus(alpha.organizationId()))
                .as("the configuration must not be left ACTIVE after a failed creation")
                .isEqualTo("PENDING");
        assertThat(paymentConfigReference(alpha.organizationId()))
                .as("and it must hold no provider account reference")
                .isNull();
        assertThat(paymentConfigAccountNumber(alpha.organizationId()))
                .as("the recorded destination survives, so the retry can search for it before "
                        + "creating anything")
                .isEqualTo(ALPHA_ACCOUNT);

        Optional<TenantPaymentAccountView> current = setupService.currentPaymentAccount();
        assertThat(current).isPresent();
        assertThat(current.orElseThrow().payable())
                .as("an unconnected cooperative cannot take payments")
                .isFalse();
    }

    /**
     * One administrator cannot touch another cooperative's payment configuration. Asserted three
     * ways, because each could fail independently.
     *
     * <ol>
     *   <li><em>Shape.</em> No public operation accepts a cooperative. There is no parameter to
     *       tamper with, so no request body, query string or path variable can name a victim.</li>
     *   <li><em>Behaviour.</em> Alpha's administrator running setup leaves Beta's row exactly as it
     *       was -- same status, same subaccount, same bank account.</li>
     *   <li><em>Database.</em> Even bypassing the application entirely,
     *       {@code uk_organization_payment_config_provider_account} refuses to let two cooperatives
     *       point at one subaccount. The last line of defence is not application code.</li>
     * </ol>
     */
    @Test
    @DisplayName("another tenant's payment configuration cannot be modified")
    void anotherTenantsConfigurationCannotBeModified() {
        connectPaymentAccount(beta, BETA_EXISTING_SUBACCOUNT);

        // 1. Shape: the only thing any operation accepts is settlement details.
        for (Method operation : TenantPaymentSetupService.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(operation.getModifiers())) {
                continue;
            }
            assertThat(operation.getParameterTypes())
                    .as("%s must not accept anything that could name a cooperative -- the tenant "
                            + "comes from the token", operation.getName())
                    .allMatch(TenantSettlementDetails.class::equals);
        }
        assertThat(Arrays.stream(TenantPaymentSetupService.class.getDeclaredMethods())
                .anyMatch(m -> "connectSettlementAccount".equals(m.getName())))
                .as("the operation under test must exist, or the loop above proved nothing")
                .isTrue();

        // 2. Behaviour: Alpha's admin sets Alpha up; Beta is a bystander.
        actingAsAdminOf(alpha);
        paystackHoldsNoSubaccounts();
        paystackCreatesSubaccount(CREATED_SUBACCOUNT, true);

        setupService.connectSettlementAccount(
                new TenantSettlementDetails(BANK_CODE, ALPHA_ACCOUNT, ALPHA_ACCOUNT_NAME));

        paystack.verify();
        assertThat(paymentConfigStatus(beta.organizationId())).isEqualTo("ACTIVE");
        assertThat(paymentConfigReference(beta.organizationId()))
                .as("Beta's settlement destination is exactly what it was")
                .isEqualTo(BETA_EXISTING_SUBACCOUNT);
        assertThat(paymentConfigAccountNumber(beta.organizationId()))
                .isEqualTo(settlementAccountNumber(beta));
        assertThat(paymentConfigReference(alpha.organizationId())).isEqualTo(CREATED_SUBACCOUNT);

        // 3. Database: two cooperatives cannot share a subaccount, whatever wrote the row.
        Throwable collision = catchThrowable(() -> jdbcTemplate.update("""
                UPDATE organization_payment_config
                   SET provider_account_reference = ?
                 WHERE organization_id = ?
                """, BETA_EXISTING_SUBACCOUNT, alpha.organizationId()));

        assertThat(collision)
                .as("uk_organization_payment_config_provider_account must refuse to point Alpha at "
                        + "Beta's settlement account")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(paymentConfigReference(alpha.organizationId()))
                .as("and Alpha still settles to its own account")
                .isEqualTo(CREATED_SUBACCOUNT);
    }

    // ------------------------------------------------------------------ Paystack expectations

    /** The provider holds no subaccount for anything: an empty, complete enumeration. */
    private void paystackHoldsNoSubaccounts() {
        paystack.expect(manyTimes(), requestTo(LOOKUP_PAGE_ONE))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"status\":true,\"data\":[]}",
                        MediaType.APPLICATION_JSON));
    }

    /** The provider already holds exactly one subaccount, settling to {@code accountNumber}. */
    private void paystackHoldsSubaccount(String accountNumber, String subaccountCode) {
        paystack.expect(manyTimes(), requestTo(LOOKUP_PAGE_ONE))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"status":true,"data":[
                          {"subaccount_code":"%s","account_number":"%s","settlement_bank":"%s",
                           "business_name":"Somebody"}]}
                        """.formatted(subaccountCode, accountNumber, BANK_CODE),
                        MediaType.APPLICATION_JSON));
    }

    /** The provider creates a subaccount, and COOPR8's request is captured for inspection. */
    private void paystackCreatesSubaccount(String subaccountCode, boolean active) {
        paystack.expect(once(), requestTo(SUBACCOUNT_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(request -> {
                    createRequest.set(((MockClientHttpRequest) request).getBodyAsString());
                    return withSuccess("""
                            {"status":true,"message":"Subaccount created",
                             "data":{"subaccount_code":"%s","active":%s,"id":12345}}
                            """.formatted(subaccountCode, active), MediaType.APPLICATION_JSON)
                            .createResponse(request);
                });
    }
}
