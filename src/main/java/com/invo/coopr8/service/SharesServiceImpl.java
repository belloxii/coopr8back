package com.invo.coopr8.service;

import com.invo.coopr8.dto.EmailDetails;
import com.invo.coopr8.dto.SharesResponse;
import com.invo.coopr8.exception.SharesException;
import com.invo.coopr8.model.Notification;
import com.invo.coopr8.model.NotificationType;
import com.invo.coopr8.model.Organization;
import com.invo.coopr8.model.Shares;
import com.invo.coopr8.model.User;
import com.invo.coopr8.repository.NotificationRepository;
import com.invo.coopr8.repository.SharesRepository;
import com.invo.coopr8.repository.UserRepository;
import com.invo.coopr8.security.AuthPrincipal;
import com.invo.coopr8.security.CurrentAuth;
import com.invo.coopr8.utils.TxnIdGen;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Shares: buying in, requesting a withdrawal, and an administrator ruling on that request.
 *
 * <p>{@code addShares} and {@code withdrawShares} act on the member the controller resolved from
 * the token, so they are self-service by construction. The approval pair is the sensitive half
 * and is guarded by {@link #requireApprovableShare(Long)}.
 */
@Service
@RequiredArgsConstructor
public class SharesServiceImpl implements SharesService {

    private final SharesRepository sharesRepository;
    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final EmailService emailService;
    private final OrganizationService organizationService;

    @Override
    public Shares addShares(User user, Shares sharesDetails) {
        BigDecimal latestBalance = user.getSharesBalance() != null ? user.getSharesBalance() : BigDecimal.ZERO;
        BigDecimal newBalance = latestBalance.add(sharesDetails.getAmount());

        Shares shares = Shares.builder()
                .user(user)
                .organization(organizationService.requireForUser(user))
                .txnId(TxnIdGen.generateTransactionId())
                .type("credit")
                .status("approved")
                .amount(sharesDetails.getAmount())
                .balance(newBalance)
                .createdAt(LocalDateTime.now())
                .build();

        // Update user balance
        user.setSharesBalance(newBalance);
        userRepository.save(user);

        return sharesRepository.save(shares);
    }

    @Override
    public SharesResponse withdrawShares(User user, Shares sharesDetails) throws SharesException {
        BigDecimal latestBalance = user.getSharesBalance() != null ? user.getSharesBalance() : BigDecimal.ZERO;
        BigDecimal amountToWithdraw = sharesDetails.getAmount();

        if (amountToWithdraw == null || amountToWithdraw.compareTo(BigDecimal.ZERO) <= 0) {
            return SharesResponse.builder()
            .responseCode("419")
            .responseMessage("Invalid withdrawal amount.")
            .build();
        }

        if (latestBalance.compareTo(amountToWithdraw) < 0) {
            return SharesResponse.builder()
            .responseCode("419")
            .responseMessage("Insufficient shares balance.")
            .build();

        }

        BigDecimal newBalance = latestBalance.subtract(amountToWithdraw);

        Shares shares = Shares.builder()
                .user(user)
                .organization(organizationService.requireForUser(user))
                .txnId(TxnIdGen.generateTransactionId())
                .type("debit")
                .status("submitted")
                .amount(amountToWithdraw)
                .balance(newBalance)
                .accountDetails(sharesDetails.getAccountDetails())
                .createdAt(LocalDateTime.now())
            .build();

        // Update user balance
        user.setSharesBalance(newBalance);
        userRepository.save(user);

        sharesRepository.save(shares);

        return SharesResponse.builder()
            .responseCode("100")
            .responseMessage("Withdrawal has been placed successfuly...")
            .shares(shares)
            .build();
    }

/**
 * Rules on a withdrawal request.
 *
 * <p><strong>Why this is transactional.</strong> The method reaches the owning cooperative through
 * {@code shareOwner.getOrganization()}, which is a LAZY {@code @ManyToOne}. Each repository call
 * above it runs in its own short transaction and closes the session on the way out, so by the time
 * the proxy is dereferenced there is no session left to load it -- "could not initialize proxy
 * [Organization#n] - no Session". Production hides this behind {@code open-in-view=true}; the test
 * profile turns that off, which is why CI sees it. One transaction around the whole method is the
 * boundary this unit of work should have had: it also makes the share update and the notification
 * commit or fail together instead of the first landing and the second not.
 *
 * <p>The tenant filter is unaffected -- {@code TenantAwareJpaTransactionManager} enables it at
 * every transaction begin, so widening the boundary applies the same filter across the whole
 * method rather than separately per repository call.
 */
@Override
@Transactional
public SharesResponse approveWithdraw(Long shareId) throws SharesException {
    Shares share = requireApprovableShare(shareId);

    if ("credit".equalsIgnoreCase(share.getType())) {
        return SharesResponse.builder()
            .responseCode("419")
            .responseMessage("No need to approve credit")
            .build();
    }

    if (!"submitted".equalsIgnoreCase(share.getStatus())) {
        return SharesResponse.builder()
            .responseCode("419")
            .responseMessage("Share has already been attended to")
            .build();
    }

    // Approve and update share
    share.setStatus("approved");
    sharesRepository.save(share);

    User shareOwner = share.getUser();
    Organization organization = organizationService.requireForUser(shareOwner);
    String orgName = organizationService.displayName(organization);

        notificationRepository.save(Notification.builder()
            .recipient(shareOwner)
            .organization(organization)
            .type(NotificationType.SHARES_APPROVED)
            .referenceId(share.getId())
            .message("Your share withdrawal of ₦" + share.getAmount()
                + " has been approved by " + orgName + ".")
            .build());

        EmailDetails emailDetails = EmailDetails.builder()
            .recipient(shareOwner.getEmail())
            .senderName(organizationService.emailSenderName(organization))
            .subject("Shares Withdrawal Approved")
            .message("Congratulations, your share withdrawal of ₦" + share.getAmount()
                + " has been approved by " + orgName + ". Login to the app to view more details."
                + organizationService.applicationUrlBlock()
                + "\n\n" + organizationService.emailSignature(organization))
            .build();

        emailService.sendEmail(emailDetails);

    return SharesResponse.builder()
        .responseCode("100")
        .responseMessage("Withdrawal has been approved successfully...")
        .shares(share)
        .build();
}

/**
 * Refuses a withdrawal request and gives the member their shares back.
 *
 * <p>Transactional for the same lazy-{@code Organization} reason as
 * {@link #approveWithdraw(Long)}, and with more at stake here: this method credits
 * {@code sharesBalance} and writes the matching {@code refunded} reversal row as two separate
 * saves. Without one boundary around them a failure between the two leaves the member's balance
 * raised with no reversal record explaining why.
 */
@Override
@Transactional
public SharesResponse declineWithdraw(Long shareId, Shares sharesDetails) throws SharesException {
    Shares share = requireApprovableShare(shareId);

    // The request body is optional (see SharesController#declineWithdraw) and only ever
    // contributes this one field, so a decline with no body at all is a decline with no remark.
    String remark = sharesDetails == null ? null : sharesDetails.getRemark();

    if ("credit".equals(share.getType())) {
        return SharesResponse.builder()
            .responseCode("419")
            .responseMessage("No need to decline credit")
            .build();
    }

    if (!"submitted".equals(share.getStatus())) {
        return SharesResponse.builder()
            .responseCode("419")
            .responseMessage("Share has already been attended to")
            .build();
    }

    // Mark the original share as declined with remark
    share.setStatus("declined");
    share.setRemark(remark);
    sharesRepository.save(share);

    // Use user from the original share instead of input
    User user = share.getUser();
    BigDecimal amount = share.getAmount();

    Organization organization = organizationService.requireForUser(user);
    String orgName = organizationService.displayName(organization);

    user.setSharesBalance(user.getSharesBalance().add(amount));
    userRepository.save(user);

    Shares shareReversal = Shares.builder()
        .txnId(TxnIdGen.generateTransactionId())
        .type("credit")
        .status("refunded")
        .amount(amount)
        .remark(remark == null ? "Reversed" : "Reversed: " + remark)
        .balance(user.getSharesBalance())
        .user(user)
        .organization(organization)
        .createdAt(LocalDateTime.now())
        .build();

    sharesRepository.save(shareReversal);

        notificationRepository.save(Notification.builder()
            .recipient(user)
            .organization(organization)
            .type(NotificationType.SHARES_DECLINED)
            .referenceId(share.getId())
            .message("Your share withdrawal of ₦" + share.getAmount()
                + " has been declined by " + orgName + ".")
            .build());

        EmailDetails emailDetails = EmailDetails.builder()
            .recipient(user.getEmail())
            .senderName(organizationService.emailSenderName(organization))
            .subject("Shares Withdrawal Declined")
            .message("This is to notify you that your share withdrawal of ₦" + share.getAmount()
                + " was declined by " + orgName
                + " and balance has been added back to your account. Login to the app to view more details."
                + organizationService.applicationUrlBlock()
                + "\n\n" + organizationService.emailSignature(organization))
            .build();

        emailService.sendEmail(emailDetails);

    return SharesResponse.builder()
        .responseCode("100")
        .responseMessage("Withdrawal has been declined successfully...")
        .shares(share)
        .build();
}

    /**
     * Loads a share withdrawal that the caller is entitled to rule on.
     *
     * <p>Two checks, in this order:
     *
     * <ol>
     *   <li><strong>Administrator.</strong> Approving a withdrawal moves money, so it is an
     *       administrative act. {@code AppConfig} also requires {@code ROLE_ADMIN} on the
     *       {@code /approve} and {@code /decline} routes, but the check is repeated here on
     *       purpose: the URL patterns are the sort of thing that gets refactored, and a share
     *       withdrawal must not become self-approvable because a path changed shape. Before
     *       Phase 2 neither layer checked, so any {@code ROLE_MEMBER} could approve their own
     *       withdrawal by calling the endpoint directly.
     *   <li><strong>Organization.</strong> The share is loaded within the administrator's own
     *       cooperative, so a share id belonging to another one is reported as absent rather
     *       than approved.
     * </ol>
     */
    private Shares requireApprovableShare(Long shareId) {
        AuthPrincipal principal = CurrentAuth.requireAdmin();

        return sharesRepository.findByIdAndOrganizationId(shareId, principal.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Share not found."));
    }
}
