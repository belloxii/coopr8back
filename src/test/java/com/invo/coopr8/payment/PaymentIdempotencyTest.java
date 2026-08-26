package com.invo.coopr8.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.invo.coopr8.model.Organization;
import com.invo.coopr8.model.PaymentProviderName;
import com.invo.coopr8.model.PaymentPurpose;
import com.invo.coopr8.model.User;
import com.invo.coopr8.repository.OrganizationRepository;
import com.invo.coopr8.repository.UserRepository;

/**
 * A payment provider will deliver the same callback more than once. That is not an edge case, it is
 * the documented behaviour of every provider worth using: retries on timeout, retries on a
 * non-2xx, and a manual "resend" button in the dashboard. A cooperative's books cannot be at the
 * mercy of how many times a webhook arrives.
 *
 * <h2>Where the guarantee comes from</h2>
 * Not from an in-memory set of seen references, which would forget on restart and would not be
 * shared between instances. It comes from two database facts:
 * <ul>
 *   <li>{@code uk_payment_transaction_provider_reference} makes a reference unique, so a payment
 *       cannot be recorded twice however concurrent the attempt.</li>
 *   <li>{@code UPDATE ... WHERE status = 'PENDING'} is a compare-and-set. Under PostgreSQL's READ
 *       COMMITTED the second updater blocks on the row lock, re-evaluates the predicate when the
 *       first commits, and reports zero rows changed -- so exactly one caller may proceed to credit
 *       the member, and it is the caller that changed the row.</li>
 * </ul>
 * The credit happens in the same transaction as that compare-and-set, so "claimed" and "credited"
 * cannot come apart: either both commit or neither does.
 *
 * <h2>Why two of these tests bypass HTTP</h2>
 * MockMvc dispatches on the calling thread, so it cannot express two callbacks arriving at once.
 * The concurrency tests therefore call {@code PaymentPostingService} directly -- which is the whole
 * of the idempotency mechanism, and being a separate bean each call really does get its own
 * transaction.
 */
class PaymentIdempotencyTest extends AbstractPaymentTest {

    private static final String REFERENCE = "C8-alpha-savings-idem";
    private static final int CONCURRENT_CALLBACKS = 2;

    @Autowired
    private PaymentPostingService postingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Test
    @DisplayName("the same reference delivered twice is processed once")
    void sameReferenceDeliveredTwiceIsProcessedOnce() throws Exception {
        reservedSavingsPayment(alpha, REFERENCE);
        paystackConfirms(REFERENCE, SAVINGS_PLAN_KOBO);

        byte[] callback = chargeSuccess(REFERENCE, SAVINGS_PLAN_KOBO);

        postSignedCallback(callback).andExpect(status().isOk());
        LocalDateTime settledAt = paymentProcessedAt(REFERENCE);

        // The provider is told 200 both times. Answering the retry with an error would only make
        // Paystack retry again, and there is nothing here for it to fix.
        postSignedCallback(callback).andExpect(status().isOk());

        assertThat(paymentStatus(REFERENCE))
                .as("the payment is settled, and settled once")
                .isEqualTo("SUCCEEDED");
        assertThat(paymentProcessedAt(REFERENCE))
                .as("the second delivery must not re-stamp the settlement time -- if it did, it "
                        + "reached the update, which means it could have reached the credit")
                .isEqualTo(settledAt);
        assertThat(countWhere("payment_transaction WHERE provider_reference = ?", REFERENCE))
                .as("one reference, one payment record")
                .isEqualTo(1L);
        assertThat(countWhere("saving WHERE channel = 'PAYSTACK'"))
                .as("one savings ledger entry, not one per delivery")
                .isEqualTo(1L);

        paystack.verify();
    }

    @Test
    @DisplayName("repeated deliveries never credit the member twice")
    void repeatedDeliveriesDoNotCreditTwice() throws Exception {
        reservedSavingsPayment(alpha, REFERENCE);
        paystackConfirms(REFERENCE, SAVINGS_PLAN_KOBO);

        byte[] callback = chargeSuccess(REFERENCE, SAVINGS_PLAN_KOBO);

        // A retry storm: what a provider does when it is unsure the first delivery landed.
        for (int delivery = 0; delivery < 5; delivery++) {
            postSignedCallback(callback).andExpect(status().isOk());
        }

        assertThat(savingsBalance(alpha.member().id()))
                .as("five deliveries of one ₦5,000 payment must add ₦5,000, not ₦25,000")
                .isEqualByComparingTo(OPENING_SAVINGS_BALANCE.add(SAVINGS_PLAN));
        assertThat(countWhere("saving WHERE channel = 'PAYSTACK'"))
                .as("one online savings entry")
                .isEqualTo(1L);
        assertThat(countWhere("repay WHERE channel = 'PAYSTACK'"))
                .as("a savings payment writes no repayment")
                .isZero();
        assertThat(sharesBalance(alpha.member().id()))
                .as("a savings payment does not touch shares")
                .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    /**
     * Two callbacks for one reference, in flight at the same moment.
     *
     * <p>The barrier is what makes this a real race rather than two sequential calls that happen to
     * be on different threads: neither posting begins until both threads are ready.
     */
    @Test
    @DisplayName("two callbacks arriving concurrently cannot both credit the payment")
    void concurrentDuplicateCannotDuplicateTheCredit() throws Exception {
        reservedSavingsPayment(alpha, REFERENCE);
        paystackMustNotBeContacted();

        CyclicBarrier bothReady = new CyclicBarrier(CONCURRENT_CALLBACKS);
        ExecutorService callbacks = Executors.newFixedThreadPool(CONCURRENT_CALLBACKS);

        List<WebhookOutcome> outcomes;
        try {
            Callable<WebhookOutcome> deliver = () -> {
                bothReady.await(20, TimeUnit.SECONDS);
                return postingService.post(PaymentProviderName.PAYSTACK, REFERENCE, SAVINGS_PLAN);
            };

            List<Future<WebhookOutcome>> running = List.of(
                    callbacks.submit(deliver), callbacks.submit(deliver));

            outcomes = List.of(
                    running.get(0).get(30, TimeUnit.SECONDS),
                    running.get(1).get(30, TimeUnit.SECONDS));
        } finally {
            callbacks.shutdownNow();
        }

        assertThat(outcomes)
                .as("exactly one caller may claim the payment; the other must be told it was a "
                        + "duplicate, having changed nothing")
                .containsExactlyInAnyOrder(WebhookOutcome.PROCESSED, WebhookOutcome.DUPLICATE);
        assertThat(savingsBalance(alpha.member().id()))
                .as("the member is credited once")
                .isEqualByComparingTo(OPENING_SAVINGS_BALANCE.add(SAVINGS_PLAN));
        assertThat(countWhere("saving WHERE channel = 'PAYSTACK'"))
                .as("one savings ledger entry, whichever thread won")
                .isEqualTo(1L);
        assertThat(paymentStatus(REFERENCE)).isEqualTo("SUCCEEDED");

        // Crediting a confirmed payment involves no provider call at all.
        paystack.verify();
    }

    /**
     * The other half of the race: two attempts to <em>record</em> the same reference.
     *
     * <p>This is the constraint doing the work, not a read-then-write check in Java, which is why it
     * would still hold if the two attempts were on different application instances. The loser is
     * refused rather than silently handed the winner's payment -- {@code PaymentService} turns that
     * refusal into "please try again", because a member who ends up attached to a payment they did
     * not start is a worse outcome than a retry.
     */
    @Test
    @DisplayName("a duplicate reference cannot be recorded twice, and the loser is refused safely")
    void duplicateReferenceInsertIsHandledSafely() {
        paystackMustNotBeContacted();

        User member = userRepository
                .findByIdAndOrganizationId(alpha.member().id(), alpha.organizationId())
                .orElseThrow();
        Organization cooperative = organizationRepository.findById(alpha.organizationId())
                .orElseThrow();

        postingService.reserve(member, cooperative, PaymentProviderName.PAYSTACK, REFERENCE,
                PaymentPurpose.SAVINGS, null, SAVINGS_PLAN);

        Throwable secondAttempt = catchThrowable(() -> postingService.reserve(
                member, cooperative, PaymentProviderName.PAYSTACK, REFERENCE,
                PaymentPurpose.SAVINGS, null, SAVINGS_PLAN));

        assertThat(secondAttempt)
                .as("the second reservation must be refused, and refused as a payment problem "
                        + "rather than leaking a persistence exception to the caller")
                .isInstanceOf(PaymentReferenceInUseException.class)
                .hasMessageContaining(REFERENCE);
        assertThat(((PaymentReferenceInUseException) secondAttempt).getReference())
                .isEqualTo(REFERENCE);

        assertThat(countWhere("payment_transaction WHERE provider_reference = ?", REFERENCE))
                .as("the constraint left exactly one row; the refused attempt rolled back")
                .isEqualTo(1L);
        assertThat(paymentStatus(REFERENCE))
                .as("the surviving row is still unsettled -- reserving is not crediting")
                .isEqualTo("PENDING");
        assertThat(savingsBalance(alpha.member().id()))
                .as("reserving a payment must not move a balance")
                .isEqualByComparingTo(OPENING_SAVINGS_BALANCE);

        paystack.verify();
    }
}
