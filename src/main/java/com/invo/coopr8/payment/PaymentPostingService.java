package com.invo.coopr8.payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.invo.coopr8.dto.RepayDto;
import com.invo.coopr8.exception.LoanException;
import com.invo.coopr8.exception.RepayException;
import com.invo.coopr8.exception.SharesException;
import com.invo.coopr8.model.Organization;
import com.invo.coopr8.model.PaymentProviderName;
import com.invo.coopr8.model.PaymentPurpose;
import com.invo.coopr8.model.PaymentStatus;
import com.invo.coopr8.model.PaymentTransaction;
import com.invo.coopr8.model.PaymentType;
import com.invo.coopr8.model.Shares;
import com.invo.coopr8.model.User;
import com.invo.coopr8.repository.PaymentTransactionRepository;
import com.invo.coopr8.repository.UserRepository;
import com.invo.coopr8.service.RepayService;
import com.invo.coopr8.service.SavingService;
import com.invo.coopr8.service.SharesService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The transactional boundary around a payment record: reserving one before the provider is called,
 * and posting one when the provider says the money arrived.
 *
 * <p><strong>Why this is a separate bean.</strong> Both methods have to be genuine transactions, and
 * a {@code @Transactional} method called from inside its own class bypasses the proxy and silently
 * runs without one. {@link PaymentService} orchestrates and makes the network calls; this class owns
 * the two database boundaries, so each transaction is exactly as short as the work that must be
 * atomic and never spans an HTTP call to a provider.
 *
 * <h2>Reserving before calling the provider</h2>
 * {@link #reserve} commits a {@code PENDING} row <em>before</em> the provider is asked to start a
 * checkout. That ordering is the fix for both defects at once: when a callback arrives, the row
 * already exists, so the cooperative and the member are read from COOPR8's own database rather than
 * from anything the caller sent, and the row is the thing a duplicate callback collides with.
 *
 * <h2>Posting at most once</h2>
 * {@link #post} claims the payment with a conditional update -- {@code SET status = SUCCEEDED WHERE
 * id = ? AND organization_id = ? AND status = PENDING} -- and credits the member only when that
 * update reports one row. This is not the same as "check, then act": under {@code READ COMMITTED}
 * the second of two concurrent updates blocks on the row lock, then re-evaluates its {@code WHERE}
 * clause against the committed new version, sees {@code SUCCEEDED}, and reports zero rows. One
 * mutation, whichever order the two callbacks arrive in.
 *
 * <p>The claim and the credit share one transaction, so a credit that fails takes the claim with it
 * and a retry can try again. Nothing is marked paid that did not also move a balance.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentPostingService {

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final UserRepository userRepository;
    private final SavingService savingService;
    private final RepayService repayService;
    private final SharesService sharesService;

    // --------------------------------------------------------------- before the provider

    /**
     * Records an intended payment, and commits it, before any provider is contacted.
     *
     * @param reference the reference COOPR8 has minted for this payment. Passed in rather than
     *                  generated here so that the caller holds one value and uses it for both the
     *                  row and the provider call.
     * @throws PaymentReferenceInUseException if the reference is already recorded. The database's
     *                                        uniqueness constraint is what detects this, so it holds
     *                                        even against a concurrent insert of the same reference.
     */
    @Transactional
    public PaymentTransaction reserve(
            User user,
            Organization organization,
            PaymentProviderName provider,
            String reference,
            PaymentPurpose purpose,
            Long targetId,
            BigDecimal amount) {

        PaymentTransaction reserved = PaymentTransaction.builder()
                .organization(organization)
                .user(user)
                .provider(provider)
                .providerReference(reference)
                .purpose(purpose)
                .targetId(targetId)
                .amount(amount)
                .status(PaymentStatus.PENDING)
                .build();

        try {
            // Flushed here rather than at commit so the constraint failure surfaces as this
            // exception, at the point where it can be turned into a refusal.
            return paymentTransactionRepository.saveAndFlush(reserved);

        } catch (DataIntegrityViolationException duplicate) {
            throw new PaymentReferenceInUseException(reference, duplicate);
        }
    }

    // ---------------------------------------------------------------- after the provider

    /**
     * Credits a payment the provider has confirmed, at most once.
     *
     * @param verifiedAmount the amount the provider's own verification reports, in naira. Compared
     *                       against the recorded amount rather than trusted in its place.
     * @return what happened. {@link WebhookOutcome#DUPLICATE} means another caller got there first
     *         and nothing was credited a second time.
     */
    @Transactional
    public WebhookOutcome post(PaymentProviderName provider, String reference,
            BigDecimal verifiedAmount) {

        PaymentTransaction payment = paymentTransactionRepository
                .findByProviderReference(provider, reference)
                .orElse(null);

        if (payment == null) {
            // Should not be reachable: the row is committed before the checkout is created.
            log.error("{} callback names reference '{}', which COOPR8 has no payment for. "
                    + "Nothing processed.", provider, reference);
            return WebhookOutcome.UNKNOWN_REFERENCE;
        }

        // The cooperative and the member come from this row. Not from the callback body, not from a
        // customer email, not from provider metadata. This is the whole of tenant resolution for an
        // unauthenticated call, and it cannot name a cooperative other than the one that started the
        // payment.
        Long organizationId = payment.getOrganization().getId();
        Long userId = payment.getUser().getId();

        if (verifiedAmount == null || verifiedAmount.compareTo(payment.getAmount()) != 0) {
            log.error("Payment {} for organization {} was verified at an amount that differs from "
                    + "the amount recorded when it started. Left unprocessed for manual posting.",
                    payment.getId(), organizationId);
            return WebhookOutcome.UNCONFIRMED;
        }

        User user = userRepository.findByIdAndOrganizationId(userId, organizationId).orElse(null);
        if (user == null) {
            log.error("Payment {} names member {} of organization {}, who no longer exists. "
                    + "Left unprocessed.", payment.getId(), userId, organizationId);
            return WebhookOutcome.UNCONFIRMED;
        }

        // Every reason not to credit is settled before the claim, so a refusal leaves the payment
        // PENDING and postable by hand rather than marked paid with nothing credited.
        String refusal = reasonNotToCredit(user, payment);
        if (refusal != null) {
            log.error("Payment {} for organization {} will not be credited: {}. Left unprocessed.",
                    payment.getId(), organizationId, refusal);
            return WebhookOutcome.UNCONFIRMED;
        }

        int claimed = paymentTransactionRepository.markSucceededWithinOrganization(
                payment.getId(), organizationId,
                PaymentStatus.PENDING, PaymentStatus.SUCCEEDED, LocalDateTime.now());

        if (claimed == 0) {
            // Already posted -- by an earlier callback, or by the one racing this. A no-op.
            log.info("Payment {} for organization {} was already posted; duplicate callback ignored.",
                    payment.getId(), organizationId);
            return WebhookOutcome.DUPLICATE;
        }

        credit(user, payment);

        log.info("Payment {} posted for organization {}: {}.",
                payment.getId(), organizationId, payment.getPurpose());
        return WebhookOutcome.PROCESSED;
    }

    // ------------------------------------------------------------------------- crediting

    /**
     * Why this payment must not be credited, or null when it may be.
     *
     * <p>Checked before the claim, deliberately. These are the conditions that would otherwise mark
     * a payment {@code SUCCEEDED} while crediting nothing.
     */
    private String reasonNotToCredit(User user, PaymentTransaction payment) {
        if (user.getPaymentType() == PaymentType.GOVERNMENT) {
            return "the member is on salary deduction and does not pay online";
        }

        if (payment.getPurpose() == PaymentPurpose.SAVINGS) {
            BigDecimal plan = user.getSavingPlan();
            if (plan == null) {
                return "the member has no savings plan to credit against";
            }
            // Savings credit the member's plan, not the amount tendered, so the two have to agree.
            // Enforced at initialization as well; repeated here because this is the side that moves
            // money.
            if (payment.getAmount().compareTo(plan) != 0) {
                return "the amount paid does not match the member's savings plan";
            }
        }

        if (payment.getPurpose() == PaymentPurpose.REPAYMENT && payment.getTargetId() == null) {
            return "the payment records no loan to repay";
        }

        return null;
    }

    /**
     * Moves the member's balance.
     *
     * <p>The checked exceptions the financial services declare are wrapped rather than logged.
     * Spring rolls back on unchecked exceptions only, so a checked exception escaping here would
     * commit the claim and leave the payment marked paid with nothing credited -- the exact failure
     * the loan work already found and fixed once.
     */
    private void credit(User user, PaymentTransaction payment) {
        try {
            switch (payment.getPurpose()) {
                case SAVINGS -> savingService.saveNow(user);

                case REPAYMENT -> repayService.repayNow(user, payment.getTargetId(),
                        RepayDto.builder()
                                .amount(payment.getAmount())
                                .type("repayment")
                                .loanId(payment.getTargetId())
                                .build());

                case SHARES -> {
                    Shares shares = new Shares();
                    shares.setAmount(payment.getAmount());
                    sharesService.addShares(user, shares);
                }
            }

        } catch (LoanException | RepayException | SharesException failed) {
            throw new PaymentCreditFailedException(payment.getId(), failed);
        }
    }
}
