package com.invo.coopr8.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import com.invo.coopr8.dto.EmailDetails;
import com.invo.coopr8.dto.LoanDto;
import com.invo.coopr8.dto.LoanResponse;
import com.invo.coopr8.exception.LoanException;
import com.invo.coopr8.model.Loan;
import com.invo.coopr8.model.Notification;
import com.invo.coopr8.model.NotificationType;
import com.invo.coopr8.model.Organization;
import com.invo.coopr8.model.User;
import com.invo.coopr8.repository.LoanRepository;
import com.invo.coopr8.repository.NotificationRepository;
import com.invo.coopr8.repository.UserRepository;
import com.invo.coopr8.security.CurrentAuth;

import lombok.AllArgsConstructor;

/**
 * Loan application, approval and rejection.
 *
 * <p>Two tenant boundaries matter here. Applying is self-service, but it names two other members
 * <em>by phone number</em>, so those lookups are scoped to the applicant's own cooperative --
 * otherwise a member could nominate a stranger from another cooperative as a guarantor, creating
 * a loan whose liability sits in one organization and whose guarantor notification sits in
 * another. Approving and rejecting are administrative, so they are scoped to the administrator's
 * cooperative and require the administrator role.
 *
 * <p><strong>The administrative decisions are transactional.</strong> Approving and rejecting each
 * make several database changes -- the loan's status, the member's running {@code loanBalance}, a
 * notification -- and neither had a transaction boundary, so every {@code save} committed on its
 * own. A failure part-way through therefore kept whatever had already committed: a member charged
 * for a loan whose status never advanced. Both now commit as a unit or not at all, and the
 * annotation is Spring's rather than Jakarta's, so the boundary is configured in the same terms as
 * the application's own {@code TenantAwareJpaTransactionManager}.
 *
 * <p><strong>Email is dispatched after commit, not inside the transaction.</strong> A message sent
 * inside the transaction can announce a decision that then rolls back, and a synchronous mail
 * failure inside the transaction would undo a decision that was financially sound. Registering the
 * send for {@code afterCommit} settles both directions: the member is only told about a decision
 * that is durable, and the mail server cannot reverse one. The failure is still surfaced -- nothing
 * here swallows it.
 */
@Service
@AllArgsConstructor
public class LoanServiceImpl implements LoanService {

    private final LoanRepository loanRepository;
    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final EmailService emailService;
    private final OrganizationService organizationService;

    @Override
    @Transactional
    public LoanResponse applyLoan(User user, LoanDto loanRequest) throws LoanException {

        // The applicant's own cooperative, which scopes every lookup below.
        Organization organization = organizationService.requireForUser(user);
        Long organizationId = organization.getId();

        // Check for existing same type loan
        Optional<Loan> existingSameTypeLoan = loanRepository.findFirstByUserAndTypeAndStatusInAndOrganizationId(
            user,
            loanRequest.getType(),
            List.of("approved", "submitted"),
            organizationId
        );

        if (existingSameTypeLoan.isPresent()) {
            return LoanResponse.builder()
                .responseCode("419")
                .responseMessage("You already applied for a " + loanRequest.getType() + " loan.")
                .build();
        }

        // Check if combining real + material
        List<Loan> activeLoans = loanRepository.findByUserAndStatusInAndOrganizationId(
            user, List.of("approved", "submitted"), organizationId);
        Set<String> activeLoanTypes = activeLoans.stream()
            .map(l -> l.getType().toLowerCase())
            .collect(Collectors.toSet());

        String newLoanType = loanRequest.getType().toLowerCase();
        if ((activeLoanTypes.contains("real") && newLoanType.equals("material")) ||
            (activeLoanTypes.contains("material") && newLoanType.equals("real"))) {
            return LoanResponse.builder()
                .responseCode("419")
                .responseMessage("You cannot combine Material and Real loans at the same time❌.")
                .build();
        }

        // Prevent using same phone number for both guarantors
        if (loanRequest.getGuarantor1().equals(loanRequest.getGuarantor2())) {
            return LoanResponse.builder()
                .responseCode("419")
                .responseMessage("You cannot use the same phone number for both guarantors❌.")
                .build();
        }

        // Prevent user from using self as guarantor
        String applicantPhone = user.getPhone();
        if (applicantPhone.equals(loanRequest.getGuarantor1()) || applicantPhone.equals(loanRequest.getGuarantor2())) {
            return LoanResponse.builder()
                .responseCode("419")
                .responseMessage("You cannot use your own phone number as a guarantor❌.")
                .build();
        }

        // Validate and retrieve guarantors -- within the applicant's own cooperative. The
        // wording stays the same for a number that belongs to another cooperative's member,
        // because from this applicant's point of view that person is indeed not a cooperator
        // here, and saying more would leak the other cooperative's membership.
        User guarantor1 = userRepository.findByPhoneAndOrganizationId(loanRequest.getGuarantor1(), organizationId)
            .orElseThrow(() -> new LoanException("The owner of the phone number " + loanRequest.getGuarantor1() + " is not a cooperator❌."));

        User guarantor2 = userRepository.findByPhoneAndOrganizationId(loanRequest.getGuarantor2(), organizationId)
            .orElseThrow(() -> new LoanException("The owner of the phone number " + loanRequest.getGuarantor2() + " is not a cooperator❌."));

        // Create loan, owned by the applicant's organization
        Loan applyLoan = Loan.builder()
            .amount(loanRequest.getAmount())
            .balance(BigDecimal.ZERO)
            .type(loanRequest.getType())
            .duration(loanRequest.getDuration())
            .user(user)
            .organization(organization)
            .accountDetails(loanRequest.getAccountDetails())
            .purpose(loanRequest.getPurpose())
            .status("submitted")
            .guarantor1(guarantor1)
            .guarantor2(guarantor2)
            .build();

        loanRepository.save(applyLoan);

        String fullName =
            (user.getFirstName() != null ? user.getFirstName() + " " : "") +
            (user.getMiddleName() != null ? user.getMiddleName() + " " : "") +
            (user.getLastName() != null ? user.getLastName() : "");

        // Send notifications AFTER loan is saved. Both guarantors are members of the applicant's
        // own cooperative (enforced above), so the notifications and the branding on the emails
        // all belong to that one organization -- a member is never shown another cooperative's
        // identity.
        notificationRepository.save(Notification.builder()
            .recipient(guarantor1)
            .organization(organization)
            .type(NotificationType.GUARANTOR_REQUEST)
            .referenceId(applyLoan.getId()) // use loan ID as reference
            .message("You have been selected as a guarantor for a loan by " + fullName)
            .timestamp(LocalDateTime.now())
            .build());

        notificationRepository.save(Notification.builder()
            .recipient(guarantor2)
            .organization(organization)
            .type(NotificationType.GUARANTOR_REQUEST)
            .referenceId(applyLoan.getId()) // use loan ID as reference
            .message("You have been selected as a guarantor for a loan by " + fullName)
            .timestamp(LocalDateTime.now())
            .build());

        emailService.sendEmail(guarantorRequestEmail(guarantor1, fullName));
        emailService.sendEmail(guarantorRequestEmail(guarantor2, fullName));

        return LoanResponse.builder()
            .responseCode("100")
            .responseMessage("Loan application submitted✅.")
            .build();
    }

    private EmailDetails guarantorRequestEmail(User guarantor, String applicantName) {
        Organization organization = guarantor.getOrganization();
        String orgName = organizationService.displayName(organization);

        return EmailDetails.builder()
            .recipient(guarantor.getEmail())
            .senderName(organizationService.emailSenderName(organization))
            .subject("Guarantor Request")
            .message("Good day, " + guarantor.getFirstName() +
                "\nYou have been selected as a guarantor for a loan by " + applicantName +
                ".\n\nKindly log into the " + orgName + " app to respond to this request." +
                organizationService.applicationUrlBlock() +
                "\n\n" + organizationService.emailSignature(organization))
            .build();
    }

    @Override
    @Transactional
    public Loan approveLoan(Long loanId) throws LoanException {
        Loan applyLoan = requireAdministrableLoan(loanId);

        Integer duration = applyLoan.getDuration();
        if (duration == null || duration <= 0) {
            throw new LoanException("Loan duration must be a positive number.");
        }

        long durationInMonths = duration.longValue(); // ✅ directly convert Integer to long

        LocalDate today = LocalDate.now();
        applyLoan.setStatus("approved");
        applyLoan.setStartDate(today);
        applyLoan.setEndDate(today.plusMonths(durationInMonths));

        // The total owed is the amount agreed, and it stays that way. The per-month instalment is
        // the rounded division of it, which for most amounts does not multiply back to the total
        // exactly -- ₦100,000 over 3 months schedules ₦33,333.33, and three of those are one kobo
        // short. Repayment closes that gap by letting the last payment settle the whole
        // outstanding balance; adjusting the stored total to make the division come out evenly
        // would instead mean the cooperative and the member disagree about the debt.
        BigDecimal amount = applyLoan.getAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal repayAmount = amount
            .divide(BigDecimal.valueOf(durationInMonths), 2, RoundingMode.HALF_UP);

        applyLoan.setAmount(amount);
        applyLoan.setBalance(amount);
        applyLoan.setRepayAmount(repayAmount);
        applyLoan.setInstallmentsPaid(0); // ✅ set as Integer

        // Approval is where the obligation is created, because approval is where the money is
        // released. Applying records a request and rejecting closes one; neither is a debt.
        User user = applyLoan.getUser();
        user.setLoanBalance(currentLoanBalance(user)
            .add(amount)
            .setScale(2, RoundingMode.HALF_UP));
        userRepository.save(user);

        Organization organization = organizationService.requireForUser(user);
        String orgName = organizationService.displayName(organization);

        // Create notification
        notificationRepository.save(Notification.builder()
            .recipient(user)
            .organization(organization)
            .type(NotificationType.LOAN_APPROVED)
            .referenceId(applyLoan.getId())
            .message("Your " + applyLoan.getType() + " loan of ₦" + amount
                + " has been approved by " + orgName + ".")
            .build());

        Loan approved = loanRepository.save(applyLoan);

        // Sent once the approval is durable -- see the class comment.
        sendAfterCommit(EmailDetails.builder()
            .recipient(user.getEmail())
            .senderName(organizationService.emailSenderName(organization))
            .subject("Loan Approved")
            .message("Congratulations, your " + applyLoan.getType() + " loan of ₦" + amount +
                " has been approved by " + orgName + ". Login to the app to view more details." +
                organizationService.applicationUrlBlock() +
                "\n\n" + organizationService.emailSignature(organization))
            .build());

        return approved;
    }

    @Override
    @Transactional
    public Loan rejectLoan(Long loanId, LoanDto loanDto) throws LoanException {
        Loan applyLoan = requireAdministrableLoan(loanId);

        // Set loan details
        applyLoan.setStatus("declined");
        applyLoan.setRemark(loanDto.getRemark());

        // The member's loan balance is deliberately untouched. This method used to add the
        // requested amount to it, so declining an application charged the member for money they
        // never received -- with no repayment schedule to work the debt off, and nothing anywhere
        // to reverse it. Nothing is disbursed by a rejection, so nothing is owed because of one.
        User user = applyLoan.getUser();

        Organization organization = organizationService.requireForUser(user);
        String orgName = organizationService.displayName(organization);

        notificationRepository.save(Notification.builder()
            .recipient(applyLoan.getUser())
            .organization(organization)
            .type(NotificationType.LOAN_DECLINED)
            .referenceId(applyLoan.getId())
            .message("Your " + applyLoan.getType() + " loan of ₦" + applyLoan.getAmount()
                + " has been declined by " + orgName + ".")
            .build());

        EmailDetails emailDetails = EmailDetails.builder()
        .recipient(user.getEmail())
        .senderName(organizationService.emailSenderName(organization))
        .subject("Loan Declined")
        .message("This is to notify you that your " + applyLoan.getType() + " loan of ₦" + applyLoan.getAmount()
            + " was declined by " + orgName + ". Login to the app to view more details."
            + organizationService.applicationUrlBlock()
            + "\n\n" + organizationService.emailSignature(organization))
        .build();

        Loan declined = loanRepository.save(applyLoan);

        sendAfterCommit(emailDetails);

        return declined;
    }

    /**
     * A member's running loan balance, treating absent as zero.
     *
     * <p>{@code users.loan_balance} is nullable with no default, so a member who has never
     * borrowed can legitimately have no value there. Reading it arithmetically has to say what
     * that means rather than fail on it half-way through a decision.
     */
    private static BigDecimal currentLoanBalance(User user) {
        BigDecimal balance = user.getLoanBalance();
        return balance == null ? BigDecimal.ZERO.setScale(2) : balance;
    }

    /**
     * Dispatches {@code emailDetails} once the current transaction has committed, or immediately
     * when there is no transaction to wait for.
     *
     * <p>The immediate branch is not a fallback for convenience: it is what makes this correct in
     * a caller that has no transaction, where "after commit" has no meaning. Nothing is swallowed
     * in either branch -- a send that fails fails visibly, it simply can no longer take a
     * committed financial decision down with it.
     */
    private void sendAfterCommit(EmailDetails emailDetails) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            emailService.sendEmail(emailDetails);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                emailService.sendEmail(emailDetails);
            }
        });
    }

    /**
     * Loads a loan the calling administrator is entitled to rule on.
     *
     * <p>Approving a loan disburses money and rejecting one closes an application, so both are
     * administrator-only and both are confined to the administrator's own cooperative. The
     * previous {@code findById} meant an administrator of any cooperative could approve any loan
     * on the platform by id.
     */
    private Loan requireAdministrableLoan(Long loanId) {
        Long organizationId = CurrentAuth.requireAdmin().organizationId();
        return loanRepository.findByIdAndOrganizationId(loanId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Loan not found."));
    }
}
