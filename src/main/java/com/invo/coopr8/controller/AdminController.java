package com.invo.coopr8.controller;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.invo.coopr8.dto.AdminUserUpdateRequest;
import com.invo.coopr8.dto.BatchUserUploadRequest;
import com.invo.coopr8.dto.BatchUserUploadResult;
import com.invo.coopr8.dto.LedgerUploadRequest;
import com.invo.coopr8.dto.LedgerUploadResult;
import com.invo.coopr8.dto.LoanDto;
import com.invo.coopr8.dto.ManualPostingRequest;
import com.invo.coopr8.exception.LoanException;
import com.invo.coopr8.exception.UserException;
import com.invo.coopr8.model.Loan;
import com.invo.coopr8.model.Notification;
import com.invo.coopr8.model.Repay;
import com.invo.coopr8.model.Saving;
import com.invo.coopr8.model.Shares;
import com.invo.coopr8.model.User;
import com.invo.coopr8.repository.LoanRepository;
import com.invo.coopr8.repository.NotificationRepository;
import com.invo.coopr8.repository.RepayRepository;
import com.invo.coopr8.repository.SavingRepository;
import com.invo.coopr8.repository.SharesRepository;
import com.invo.coopr8.repository.UserRepository;
import com.invo.coopr8.security.CurrentAuth;
import com.invo.coopr8.service.AdminLedgerService;
import com.invo.coopr8.service.BatchUserUploadService;
import com.invo.coopr8.service.CloudinaryService;
import com.invo.coopr8.service.LoanService;
import com.invo.coopr8.service.UserService;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Administration of <em>one</em> cooperative.
 *
 * <p>{@code ROLE_ADMIN} is required for every route here (see {@code AppConfig}), but a role is
 * only half the answer -- the other half is <em>whose</em> administrator. Before Phase 2 the
 * list endpoints called {@code findAll()}, so an administrator of any cooperative on the
 * platform received every member, loan, repayment, saving, share and notification belonging to
 * every other cooperative. Each one is now scoped to {@link #organizationId()}, which comes from
 * the caller's verified token and cannot be influenced by the request.
 *
 * <p>Single-record lookups answer {@code 404} for an id belonging to another cooperative, rather
 * than {@code 403}: "forbidden" would confirm the record exists somewhere on the platform.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@AllArgsConstructor
public class AdminController {

    /** Statuses that mean "signed up, not yet approved". Self-service signups land as PENDING. */
    private static final Set<String> AWAITING_APPROVAL = Set.of("NEW", "PENDING");

    private final UserService userService;
    private final BatchUserUploadService batchUserUploadService;
    private final CloudinaryService cloudinaryService;
    private final AdminLedgerService adminLedgerService;
    private final LoanRepository loanRepository;
    private final UserRepository userRepository;
    private final LoanService loanService;
    private final RepayRepository repayRepository;
    private final NotificationRepository notificationRepository;
    private final SavingRepository savingRepository;
    private final SharesRepository sharesRepository;

    /** The calling administrator's own cooperative. Read from the token, never from the request. */
    private Long organizationId() {
        return CurrentAuth.requireOrganizationId();
    }

    // ============================================================================ members

    @GetMapping("/users")
    public List<User> allUsers() {
        return userRepository.findAllByOrganizationIdOrderByIdAsc(organizationId());
    }

    @GetMapping("/user/id/{userId}")
    public User findUserById(@PathVariable Long userId) throws UserException {
        return userRepository.findByIdAndOrganizationId(userId, organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Member not found."));
    }

    @GetMapping("/user/ledgerID/{ledgerID}")
    public User findUserByledgerID(@PathVariable String ledgerID) throws UserException {
        return userRepository.findByLedgerIDAndOrganizationId(ledgerID, organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Member not found."));
    }

    /**
     * Members awaiting approval.
     *
     * <p>Matches NEW <em>and</em> PENDING. The previous version compared against the literal
     * {@code "new"} while self-service signups are created as {@code PENDING}, so it could not
     * return the very records it exists to list.
     */
    @GetMapping("/users/new")
    public List<User> findNewUsers() throws UserException {
        return userRepository.findByOrganizationIdAndStatusIn(organizationId(), AWAITING_APPROVAL);
    }

    @PutMapping("/user/{userId}/activate")
    public User activateUser(@PathVariable Long userId) throws UserException {
        return userService.activateUser(userId);
    }

    /**
     * Editing a member of this cooperative.
     *
     * <p>Takes {@link AdminUserUpdateRequest} rather than a {@code User} entity. The entity form
     * accepted {@code ledgerID} and {@code password} straight from the body, and had no notion of
     * which organization the target belonged to.
     */
    @PutMapping("/user/update")
    public User updateUser(@Valid @RequestBody AdminUserUpdateRequest request) throws UserException {
        return userService.adminUpdateUser(request);
    }

    @PostMapping("/users/multi")
    public BatchUserUploadResult addMultiUsers(@RequestBody BatchUserUploadRequest request) {
        return batchUserUploadService.upload(request);
    }

    /**
     * One-time migration: move existing Google Drive passport links to Cloudinary.
     *
     * <p>Scoped to the caller's own members. Unscoped, this rewrote passport URLs on member
     * records across every cooperative on the platform -- a write, not just a read.
     */
    @PostMapping("/users/migrate-drive-passports")
    public ResponseEntity<Map<String, Object>> migrateDrivePassports() {

        List<User> driveUsers = userRepository.findAllByOrganizationIdOrderByIdAsc(organizationId())
                .stream()
                .filter(u -> cloudinaryService.isDriveLink(u.getPassport()))
                .toList();

        int success = 0;
        int failed = 0;
        List<String> failures = new ArrayList<>();

        for (User user : driveUsers) {
            try {
                String cloudinaryUrl = cloudinaryService.uploadImageFromUrl(user.getPassport()).get("url");
                user.setPassport(cloudinaryUrl);
                userRepository.save(user);
                success++;
            } catch (Exception e) {
                failed++;
                failures.add(user.getLedgerID() + ": " + e.getMessage());
            }
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("total", driveUsers.size());
        summary.put("success", success);
        summary.put("failed", failed);
        summary.put("failures", failures);
        return ResponseEntity.ok(summary);
    }

    // ================================================================= ledger and postings

    /** Salary-deduction ledger upload (PSN members). Matches members within this cooperative. */
    @PostMapping("/ledger/batch")
    public LedgerUploadResult uploadLedger(@RequestBody LedgerUploadRequest request) {
        return adminLedgerService.batchUpload(request);
    }

    /** Manual posting from a transfer receipt (SELF_PAY members). */
    @PostMapping("/savings/manual")
    public ResponseEntity<Saving> postManualSaving(@RequestBody ManualPostingRequest request) {
        return ResponseEntity.ok(adminLedgerService.manualSaving(request));
    }

    @PostMapping("/repays/manual")
    public ResponseEntity<?> postManualRepay(@RequestBody ManualPostingRequest request) {
        BigDecimal applied = adminLedgerService.manualRepay(request);
        return ResponseEntity.ok(Map.of("responseCode", "100",
                "responseMessage", "Repayment of ₦" + applied.toPlainString() + " recorded.",
                "amountApplied", applied));
    }

    // ============================================================================== loans

    @GetMapping("/loan/loansbyuserid/{userId}")
    public List<Loan> loansByUserId(@PathVariable Long userId) throws LoanException {
        return loanRepository.findByUser_IdAndOrganizationId(userId, organizationId());
    }

    @GetMapping("/loan/all")
    public List<Loan> allLoans() throws LoanException {
        return loanRepository.findAllByOrganizationIdOrderByIdDesc(organizationId());
    }

    @PutMapping("/loan/{loanId}/approve")
    public Loan approveLoan(@PathVariable Long loanId) throws LoanException {
        return loanService.approveLoan(loanId);
    }

    @PutMapping("/loan/{loanId}/reject")
    public Loan rejectLoan(@PathVariable Long loanId, @RequestBody LoanDto loanDto)
            throws LoanException {
        return loanService.rejectLoan(loanId, loanDto);
    }

    // ========================================================================= repayments

    @GetMapping("/repays/all")
    public List<Repay> allRepay() {
        return repayRepository.findAllByOrganizationIdOrderByIdDesc(organizationId());
    }

    @GetMapping("/repays/user/{userId}")
    public List<Repay> userRepay(@PathVariable Long userId) {
        return repayRepository.findByUser_IdAndOrganizationId(userId, organizationId());
    }

    // ============================================================ savings, shares, notices

    @GetMapping("/savings/all")
    public List<Saving> allSavings() {
        return savingRepository.findAllByOrganizationIdOrderByIdDesc(organizationId());
    }

    @GetMapping("/savings/user/{userId}")
    public List<Saving> savingByUserId(@PathVariable Long userId) {
        return savingRepository.findByUser_IdAndOrganizationId(userId, organizationId());
    }

    @GetMapping("/shares/all")
    public ResponseEntity<List<Shares>> getAllShares() {
        return ResponseEntity.ok(
                sharesRepository.findAllByOrganizationIdOrderByIdDesc(organizationId()));
    }

    /**
     * One member's shares history.
     *
     * <p>Replaces {@code GET /api/shares/user/{userId}}, which any member could call for any
     * member id.
     */
    @GetMapping("/shares/user/{userId}")
    public ResponseEntity<List<Shares>> sharesByUserId(@PathVariable Long userId) {
        return ResponseEntity.ok(
                sharesRepository.findByUser_IdAndOrganizationId(userId, organizationId()));
    }

    @GetMapping("/notis/all")
    public ResponseEntity<List<Notification>> getAllNotifications() {
        return ResponseEntity.ok(
                notificationRepository.findAllByOrganizationIdOrderByTimestampDesc(organizationId()));
    }
}
