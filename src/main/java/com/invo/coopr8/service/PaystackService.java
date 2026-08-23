package com.invo.coopr8.service;

import com.invo.coopr8.dto.PaystackInitializeRequest;
import com.invo.coopr8.dto.PaystackInitializeResponse;
import com.invo.coopr8.dto.RepayDto;
import com.invo.coopr8.exception.LoanException;
import com.invo.coopr8.exception.RepayException;
import com.invo.coopr8.exception.SharesException;
import com.invo.coopr8.model.Organization;
import com.invo.coopr8.model.PaymentType;
import com.invo.coopr8.model.Shares;
import com.invo.coopr8.model.User;
import com.invo.coopr8.repository.UserRepository;
import com.invo.coopr8.tenant.TenantResolver;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Paystack: starting a card payment, and acting on the callback that says it succeeded.
 *
 * <p><strong>The webhook is the application's only unauthenticated write path</strong>, which
 * makes it the one place where tenant identity cannot come from a token. Two rules follow, and
 * both are new in Phase 2.
 *
 * <p><strong>1. The inbound body decides nothing.</strong> Anyone on the internet can POST to the
 * webhook URL. The old implementation read the email address, the amount and the transaction type
 * straight out of that body and used the {@code /transaction/verify} call only as a true/false
 * gate -- so a request quoting any genuinely successful reference could name a different member
 * and a larger amount, and be believed. Everything is now read from the <em>verified</em>
 * response that Paystack returns over an authenticated call, and the inbound body is used for
 * nothing but the reference to look up.
 *
 * <p><strong>2. The cooperative comes from the transaction's own metadata.</strong>
 * {@link #initializeTransaction} stamps the paying member's organization slug into the metadata
 * when the payment starts, so the verified transaction carries it back. The member is then found
 * by email <em>within that cooperative</em>. This matters because email is unique per
 * cooperative, not globally: the same address may belong to a member of two different
 * cooperatives, and a global {@code findByEmail} would credit whichever row the database happened
 * to return first. When the metadata carries no resolvable organization the payment is left
 * unprocessed for an operator to look at -- there is no "if there is only one organization" and
 * no default tenant.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaystackService {

    /** Metadata key carrying the paying member's cooperative. */
    private static final String ORGANIZATION_KEY = "organization";

    @Value("${paystack.secret.key}")
    private String paystackSecretKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final UserRepository userRepository;
    private final UserService userService;
    private final SavingService savingService;
    private final RepayService repaymentService;
    private final SharesService sharesService;
    private final TenantResolver tenantResolver;
    private final OrganizationService organizationService;

    /**
     * Starts a transaction for the authenticated member.
     *
     * <p>The email address and the organization are taken from the caller's own record rather
     * than from the request body. A member could previously initialize a payment under somebody
     * else's email address, and because the webhook credited by email, the payment would land on
     * that other member's account.
     */
    public PaystackInitializeResponse initializeTransaction(PaystackInitializeRequest request) {
        User user = userService.requireCurrentUser();
        Organization organization = organizationService.requireForUser(user);

        // Salary-deduction members do not pay online.
        if (user.getPaymentType() == PaymentType.GOVERNMENT) {
            PaystackInitializeResponse blocked = new PaystackInitializeResponse();
            blocked.setStatus(false);
            blocked.setMessage("Your savings and repayments are deducted from your salary "
                    + "automatically. Online payment is not required.");
            return blocked;
        }

        request.setEmail(user.getEmail());

        // Carry the tenant through the gateway so the webhook can find its way back to it.
        Map<String, Object> metadata = request.getMetadata() == null
                ? new java.util.HashMap<>()
                : new java.util.HashMap<>(request.getMetadata());
        metadata.put(ORGANIZATION_KEY, organization.getSlug());
        request.setMetadata(metadata);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(paystackSecretKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<PaystackInitializeRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<PaystackInitializeResponse> response = restTemplate.postForEntity(
            "https://api.paystack.co/transaction/initialize",
            entity,
            PaystackInitializeResponse.class
        );

        return response.getBody();
    }

    public Map<String, Object> verifyTransaction(String reference) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(paystackSecretKey);

        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            "https://api.paystack.co/transaction/verify/" + reference,
            HttpMethod.GET,
            entity,
            new ParameterizedTypeReference<>() {}
        );

        return response.getBody();
    }

    @Transactional
    public void handleWebhook(Map<String, Object> payload) {
        try {
            // Step 1: event type, and the reference -- the only two things taken from the body.
            String event = (String) payload.get("event");
            if (!"charge.success".equalsIgnoreCase(event)) {
                log.warn("Skipping Paystack event: {}", event);
                return;
            }

            String reference = referenceOf(payload);
            if (reference == null) {
                log.warn("Paystack webhook carried no transaction reference; ignoring.");
                return;
            }

            // Step 2: ask Paystack what actually happened. Everything below reads from this.
            Map<String, Object> verified = verifyTransaction(reference);
            if (verified == null || !Boolean.TRUE.equals(verified.get("status"))) {
                log.warn("Paystack verification failed for reference: {}", reference);
                return;
            }
            if (!(verified.get("data") instanceof Map<?, ?> data)) {
                log.warn("Paystack verification returned no data for reference: {}", reference);
                return;
            }
            if (!"success".equalsIgnoreCase(String.valueOf(data.get("status")))) {
                log.warn("Paystack transaction {} is not successful ({}); ignoring.",
                        reference, data.get("status"));
                return;
            }

            if (!(data.get("customer") instanceof Map<?, ?> customer)) {
                log.warn("Verified transaction {} has no customer object.", reference);
                return;
            }
            if (!(data.get("metadata") instanceof Map<?, ?> metadata)) {
                log.warn("Verified transaction {} has no metadata object.", reference);
                return;
            }

            String email = (String) customer.get("email");
            if (email == null || email.isBlank()) {
                log.warn("Verified transaction {} has no customer email.", reference);
                return;
            }

            // Step 3: which cooperative. Fails closed -- no default tenant, no "only one".
            String slug = asText(metadata.get(ORGANIZATION_KEY));
            Organization organization = slug == null
                    ? null
                    : tenantResolver.activeOrganizationBySlug(slug).orElse(null);
            if (organization == null) {
                log.error("Cannot process Paystack transaction {}: metadata names no resolvable "
                        + "cooperative (organization={}). Left unprocessed for manual posting.",
                        reference, slug);
                return;
            }

            // Step 4: the member, within that cooperative.
            User user = userRepository
                    .findByEmailIgnoreCaseAndOrganizationId(email, organization.getId())
                    .orElse(null);
            if (user == null) {
                log.warn("Paystack transaction {}: no member of organization {} matches the "
                        + "paying email address.", reference, organization.getId());
                return;
            }

            if (user.getPaymentType() == PaymentType.GOVERNMENT) {
                log.warn("Skipping online payment for salary-deduction member {}", user.getId());
                return;
            }

            // Step 5: amount and type, from the verified transaction.
            Object amountObj = data.get("amount");
            if (amountObj == null) {
                log.warn("Verified transaction {} has no amount.", reference);
                return;
            }
            BigDecimal amount = new BigDecimal(amountObj.toString())
                    .divide(BigDecimal.valueOf(100));

            String type = asText(metadata.get("type"));
            if (type == null) {
                log.warn("Transaction {} has no type in metadata (member {}).", reference, user.getId());
                return;
            }

            // Step 6: handle by type
            switch (type.toLowerCase()) {
                case "savings" -> handleSavings(user, amount);
                case "repayment" -> handleRepayment(user, metadata, amount);
                case "shares" -> handleShares(user, amount);
                default -> log.warn("Unknown transaction type '{}' for member {}", type, user.getId());
            }

        } catch (Exception e) {
            log.error("Exception while handling Paystack webhook", e);
        }
    }

    /** The reference, from either the flat body or its {@code data} object. */
    private String referenceOf(Map<String, Object> payload) {
        String reference = asText(payload.get("reference"));
        if (reference != null) {
            return reference;
        }
        return payload.get("data") instanceof Map<?, ?> data ? asText(data.get("reference")) : null;
    }

    private static String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private void handleSavings(User user, BigDecimal amount) {
        if (user.getSavingPlan() == null) {
            log.warn("Member {} has no plan set for savings", user.getId());
            return;
        }

        if (amount.compareTo(user.getSavingPlan()) == 0) {
            savingService.saveNow(user);
            log.info("Savings processed for member {} (₦{})", user.getId(), amount);
        } else {
            log.warn("Amount mismatch: paid ₦{}, expected ₦{} for member {}",
                    amount, user.getSavingPlan(), user.getId());
        }
    }

    private void handleRepayment(User user, Map<?, ?> metadata, BigDecimal amount)
            throws LoanException, RepayException {
        Object loanIdObj = metadata.get("loanId");
        if (loanIdObj == null) {
            log.warn("Missing loanId in repayment metadata for member {}", user.getId());
            return;
        }

        try {
            Long loanId = Long.parseLong(loanIdObj.toString());
            RepayDto repayDto = RepayDto.builder()
                .amount(amount)
                .type("repayment")
                .loanId(loanId)
                .build();

            // repayNow scopes the loan to this member's own cooperative and to this member,
            // so a loanId planted in metadata cannot be pointed at anyone else's loan.
            repaymentService.repayNow(user, loanId, repayDto);
            log.info("Repayment processed for member {}, loanId={}, amount=₦{}",
                    user.getId(), loanId, amount);

        } catch (NumberFormatException e) {
            log.warn("Invalid loanId format in metadata for member {}", user.getId());
        }
    }

    private void handleShares(User user, BigDecimal amount) throws SharesException {
        Shares sharesDetails = new Shares();
        sharesDetails.setAmount(amount);
        sharesService.addShares(user, sharesDetails);
        log.info("Shares processed for member {} (₦{})", user.getId(), amount);
    }
}
