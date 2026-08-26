package com.invo.coopr8.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The provider callback is the one unauthenticated endpoint that can move money, so the signature is
 * the whole of its authentication.
 *
 * <h2>What these tests pin down</h2>
 * <ul>
 *   <li>A body signed with the platform's Paystack secret is honoured.</li>
 *   <li>No signature, a wrong signature, or a signature that belonged to a <em>different</em> body
 *       is refused with 401 -- the last being the case that matters most, because it is the shape an
 *       attacker who has seen one real callback would try.</li>
 *   <li>A refused callback moves nothing and asks Paystack nothing. Verification happens before the
 *       body is parsed, so a forged body never reaches the code that credits members.</li>
 * </ul>
 *
 * <h2>Why the callback is credible in the first place</h2>
 * COOPR8 does not take the callback's word for the amount. The reference must already exist as a
 * {@code PENDING} payment_transaction this platform created, and the amount is read back from
 * Paystack's verification endpoint rather than from the callback body. The signature check is the
 * outermost of those three gates; these tests exercise it, and
 * {@link PaymentIdempotencyTest} exercises what happens after it passes.
 */
class PaystackWebhookSecurityTest extends AbstractPaymentTest {

    private static final String REFERENCE = "C8-alpha-savings-1";

    /**
     * A signature of the right shape and length that is nonetheless wrong. 128 hex characters,
     * because a rejection that only worked for malformed input would be no protection at all.
     */
    private static final String WELL_FORMED_BUT_WRONG_SIGNATURE = "f".repeat(128);

    @Test
    @DisplayName("a correctly signed charge.success is accepted and credits the member")
    void validSignatureIsAccepted() throws Exception {
        reservedSavingsPayment(alpha, REFERENCE);
        paystackConfirms(REFERENCE, SAVINGS_PLAN_KOBO);

        byte[] body = chargeSuccess(REFERENCE, SAVINGS_PLAN_KOBO);

        postSignedCallback(body)
                .andExpect(status().isOk());

        assertThat(paymentStatus(REFERENCE))
                .as("a signed, verified callback must settle the payment it names")
                .isEqualTo("SUCCEEDED");
        assertThat(paymentProcessedAt(REFERENCE))
                .as("a settled payment records when it was settled")
                .isNotNull();
        assertThat(savingsBalance(alpha.member().id()))
                .as("the member's savings must grow by exactly their monthly plan")
                .isEqualByComparingTo(OPENING_SAVINGS_BALANCE.add(SAVINGS_PLAN));
        assertThat(countWhere("saving WHERE channel = 'PAYSTACK'"))
                .as("exactly one online savings record, for this one payment")
                .isEqualTo(1L);

        paystack.verify();
    }

    @Test
    @DisplayName("a callback with no signature header is rejected")
    void missingSignatureIsRejected() throws Exception {
        reservedSavingsPayment(alpha, REFERENCE);
        paystackMustNotBeContacted();

        postCallback(chargeSuccess(REFERENCE, SAVINGS_PLAN_KOBO), null)
                .andExpect(status().isUnauthorized());

        assertNothingWasCredited();
    }

    @Test
    @DisplayName("a callback with a wrong signature is rejected")
    void invalidSignatureIsRejected() throws Exception {
        reservedSavingsPayment(alpha, REFERENCE);
        paystackMustNotBeContacted();

        postCallback(chargeSuccess(REFERENCE, SAVINGS_PLAN_KOBO), WELL_FORMED_BUT_WRONG_SIGNATURE)
                .andExpect(status().isUnauthorized());

        assertNothingWasCredited();
    }

    /**
     * The replay-with-tampering case: capture a genuine callback, change the amount, keep the
     * signature.
     *
     * <p>This is the reason the endpoint takes {@code byte[]} rather than a parsed object. The HMAC
     * is computed over the exact bytes received, so re-serialising the body -- even to something
     * semantically identical -- would compute a different digest and either break honest callbacks
     * or, worse, let a tampered one through by verifying a normalised form of it.
     */
    @Test
    @DisplayName("a modified payload sent with the original body's signature is rejected")
    void modifiedPayloadWithOriginalSignatureIsRejected() throws Exception {
        reservedSavingsPayment(alpha, REFERENCE);
        paystackMustNotBeContacted();

        byte[] genuine = chargeSuccess(REFERENCE, SAVINGS_PLAN_KOBO);
        String genuineSignature = signatureFor(genuine);

        byte[] tampered = chargeSuccess(REFERENCE, SAVINGS_PLAN_KOBO * 20);
        assertThat(new String(tampered, StandardCharsets.UTF_8))
                .as("the test must actually have changed the body it is replaying the signature for")
                .isNotEqualTo(new String(genuine, StandardCharsets.UTF_8));

        postCallback(tampered, genuineSignature)
                .andExpect(status().isUnauthorized());

        assertNothingWasCredited();
    }

    /**
     * The financial consequence of a rejection, stated on its own.
     *
     * <p>The body used here is one that <em>would</em> have credited the member: the reference is
     * real, the amount matches the member's plan, and Paystack would confirm it. The only thing
     * wrong with it is the signature. So this test isolates the claim that verification runs before
     * anything is read, asked or written -- not merely that a 401 comes back.
     */
    @Test
    @DisplayName("a rejected callback leaves every balance, ledger and payment untouched")
    void rejectedCallbackCausesNoFinancialMutation() throws Exception {
        reservedSavingsPayment(alpha, REFERENCE);
        paystackMustNotBeContacted();

        BigDecimal savingsBefore = savingsBalance(alpha.member().id());
        BigDecimal sharesBefore = sharesBalance(alpha.member().id());
        long savingRowsBefore = countWhere("saving");
        long repayRowsBefore = countWhere("repay");
        long shareRowsBefore = countWhere("shares");

        postCallback(chargeSuccess(REFERENCE, SAVINGS_PLAN_KOBO), WELL_FORMED_BUT_WRONG_SIGNATURE)
                .andExpect(status().isUnauthorized());

        assertThat(savingsBalance(alpha.member().id()))
                .as("savings balance").isEqualByComparingTo(savingsBefore);
        assertThat(sharesBalance(alpha.member().id()))
                .as("shares balance").isEqualByComparingTo(sharesBefore);
        assertThat(countWhere("saving")).as("savings ledger rows").isEqualTo(savingRowsBefore);
        assertThat(countWhere("repay")).as("repayment ledger rows").isEqualTo(repayRowsBefore);
        assertThat(countWhere("shares")).as("shares ledger rows").isEqualTo(shareRowsBefore);
        assertThat(paymentStatus(REFERENCE))
                .as("the payment stays unsettled and remains claimable by a genuine callback")
                .isEqualTo("PENDING");
        assertThat(paymentProcessedAt(REFERENCE)).as("nothing was settled").isNull();

        // And Paystack was never asked to verify anything: rejection precedes every provider call.
        paystack.verify();
    }

    /** The shared "nothing happened" assertion for the three rejection shapes. */
    private void assertNothingWasCredited() {
        assertThat(paymentStatus(REFERENCE))
                .as("a refused callback must not settle the payment")
                .isEqualTo("PENDING");
        assertThat(savingsBalance(alpha.member().id()))
                .as("a refused callback must not move a member's savings")
                .isEqualByComparingTo(OPENING_SAVINGS_BALANCE);
        assertThat(countWhere("saving WHERE channel = 'PAYSTACK'"))
                .as("a refused callback must not write an online savings record")
                .isZero();

        paystack.verify();
    }
}
