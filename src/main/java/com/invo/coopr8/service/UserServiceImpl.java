package com.invo.coopr8.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.invo.coopr8.config.JwtProvider;
import com.invo.coopr8.dto.AdminUserUpdateRequest;
import com.invo.coopr8.dto.AuthResponse;
import com.invo.coopr8.dto.CoopResponse;
import com.invo.coopr8.dto.EmailDetails;
import com.invo.coopr8.dto.ForgotPasswordRequest;
import com.invo.coopr8.dto.LoginDto;
import com.invo.coopr8.dto.PasswordDto;
import com.invo.coopr8.dto.ResetPasswordRequest;
import com.invo.coopr8.dto.UserProfileUpdateRequest;
import com.invo.coopr8.dto.UserRequest;
import com.invo.coopr8.exception.UserException;
import com.invo.coopr8.model.Organization;
import com.invo.coopr8.model.OtpPurpose;
import com.invo.coopr8.model.PaymentType;
import com.invo.coopr8.model.Role;
import com.invo.coopr8.model.User;
import com.invo.coopr8.repository.UserRepository;
import com.invo.coopr8.security.AuthPrincipal;
import com.invo.coopr8.security.CurrentAuth;
import com.invo.coopr8.tenant.TenantResolver;
import com.invo.coopr8.utils.LedgerIDGen;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    /** Password every new member starts with, and is forced to change on first sign-in. */
    private static final String DEFAULT_PASSWORD = "123456";

    private static final Pattern STRONG_PASSWORD = Pattern.compile(
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,}$");

    private static final String STRENGTH_MESSAGE =
            "Password must be at least 8 characters long and include uppercase, lowercase, "
                    + "number, and special character";

    /** One message for every credential failure. See {@link #invalidCredentials()}. */
    private static final String INVALID_CREDENTIALS = "Invalid membership number or password.";

    /** One message for every reset-code failure. See {@link #resetPassword}. */
    private static final String INVALID_RESET =
            "That reset code is not valid. Please request a new one.";

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final OrganizationService organizationService;
    private final OTPService otpService;
    private final TenantResolver tenantResolver;

    /**
     * A hash to compare against when no member matched, so a failed login costs the same
     * whether or not the membership number exists. Computed once, lazily; the value is
     * irrelevant as long as nothing can match it.
     */
    private volatile String timingDecoyHash;

    // ====================================================================== membership

    @Override
    @Transactional
    public AuthResponse createAccount(UserRequest request) {
        // Self-service signups start as PENDING and await that cooperative's own approval.
        return createAccount(request, "PENDING");
    }

    @Override
    @Transactional
    public AuthResponse createAccount(UserRequest request, String status) {

        // Which cooperative this member joins. For an authenticated administrator it is their
        // own, taken from the JWT; for an anonymous signup it is the slug in the URL they came
        // from. request.getOrganization() can therefore never move a member into a tenant the
        // caller does not already belong to.
        Organization organization = organizationService.resolveRequestedOrganization(
                request.getOrganization());

        if (!LedgerIDGen.isValidPrefix(organization.getLedgerPrefix())) {
            // Refuse rather than invent a prefix: a membership number is permanent and printed
            // on ledgers, so a placeholder would outlive the misconfiguration that caused it.
            log.error("Organization {} has an unusable ledger prefix; cannot issue membership numbers.",
                    organization.getId());
            return failedAuth("This cooperative is not yet configured to issue membership numbers. "
                    + "Please contact an administrator.");
        }

        // Duplicate checks are per-cooperative. One person may legitimately be a member of two
        // cooperatives on this platform with the same email address and phone number; what must
        // not happen is two records for that person inside one cooperative.
        Optional<User> existing = userRepository.findByEmailIgnoreCaseAndOrganizationId(
                request.getEmail(), organization.getId());
        if (existing.isPresent()) {
            String existingStatus = existing.get().getStatus();
            if ("NEW".equalsIgnoreCase(existingStatus) || "PENDING".equalsIgnoreCase(existingStatus)) {
                return failedAuth("Your account has already been created and is awaiting approval.");
            }
            return failedAuth("Email already exists.");
        }

        if (userRepository.existsByPhoneAndOrganizationId(request.getPhone(), organization.getId())) {
            return failedAuth("Phone number already exists.");
        }

        // Membership numbers are sequential within the organization and carry that
        // organization's own prefix -- there is no platform-wide sequence and no legacy prefix.
        Integer lastLedger = userRepository.findMaxLedgerNumberByOrganization(organization.getId());
        int nextLedger = (lastLedger == null) ? 1 : lastLedger + 1;
        String ledgerID = LedgerIDGen.generate(organization.getLedgerPrefix(), nextLedger);

        User user = User.builder()

                // Personal Information
                .firstName(request.getFirstName())
                .middleName(request.getMiddleName())
                .lastName(request.getLastName())
                .gender(request.getGender())
                .marital(request.getMarital())

                // Contact
                .address(request.getAddress())
                .homeTown(request.getHomeTown())
                .state(request.getState())
                .lga(request.getLga())
                .station(request.getStation())
                .phone(request.getPhone())
                .email(request.getEmail())

                // Identification
                .psn(request.getPsn())
                .occupation(request.getOccupation())
                .verNo(request.getVerNo())
                .passport(request.getPassport())

                // Payment method (defaults to SELF_PAY when not supplied)
                .paymentType(request.getPaymentType() == null
                        ? PaymentType.SELF_PAY
                        : request.getPaymentType())

                // Membership
                .ledgerID(ledgerID)
                .ledgerNumber(nextLedger)

                // Financial Plans
                .savingPlan(orZero(request.getSavingPlan()))
                .specialSavingPlan(orZero(request.getSpecialSavingPlan()))
                .sharePlan(orZero(request.getSharePlan()))

                // Balances
                .savingsBalance(BigDecimal.ZERO)
                .loanBalance(BigDecimal.ZERO)
                .sharesBalance(BigDecimal.ZERO)
                .totalPurchase(BigDecimal.ZERO)
                .profit(BigDecimal.ZERO)

                // Next of Kin
                .nextOfKin(request.getNextOfKin())
                .nextOfKinRelationship(request.getNextOfKinRelationship())
                .nextOfKinAddress(request.getNextOfKinAddress())
                .nextOfKinPhone(request.getNextOfKinPhone())

                // Account. The role is fixed here: a signup body cannot ask for ROLE_ADMIN,
                // because UserRequest has no role field and this line does not read one.
                .password(passwordEncoder.encode(DEFAULT_PASSWORD))
                .status(status)
                .role(Role.ROLE_MEMBER)

                // Tenant ownership
                .organization(organization)

                .build();

        User savedUser = userRepository.save(user);

        emailService.sendEmail(welcomeEmail(savedUser, organization, status));

        return AuthResponse.builder()
                .responseCode("100")
                .responseMessage("Account created successfully. Kindly check your email.")
                .user(savedUser)
                .build();
    }

    @Override
    public List<AuthResponse> addMultiUsers(List<UserRequest> requests) throws UserException {
        return requests.stream()
                .map(this::createAccount)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // ================================================================== authentication

    /**
     * {@inheritDoc}
     *
     * <p>The order below is the security-relevant part:
     * <ol>
     *   <li>Resolve the organization. Until this succeeds there is nothing to search.</li>
     *   <li>Look the membership number up <em>inside that organization only</em>.</li>
     *   <li>Verify the password.</li>
     *   <li><em>Then</em> consider account status.</li>
     * </ol>
     *
     * <p>Step 4 comes last on purpose. Previously the "awaiting approval" branch ran before the
     * password was checked, so anyone could type a membership number and learn that it existed
     * and was pending. Every pre-password failure now yields one indistinguishable response.
     */
    @Override
    public AuthResponse login(LoginDto loginDto) {

        String ledgerID = trimToNull(loginDto.getLedgerID());
        String password = loginDto.getPassword();
        if (ledgerID == null || !StringUtils.hasText(password)) {
            return invalidCredentials();
        }

        Organization organization = resolveLoginOrganization(loginDto.getOrganization(), ledgerID);
        if (organization == null) {
            return invalidCredentials();
        }

        User user = userRepository
                .findByLedgerIDAndOrganizationId(ledgerID, organization.getId())
                .orElse(null);

        if (user == null) {
            // Burn a comparable amount of time so "no such member" and "wrong password" are not
            // distinguishable by how quickly the answer comes back.
            passwordEncoder.matches(password, timingDecoyHash());
            return invalidCredentials();
        }

        if (!passwordEncoder.matches(password, user.getPassword())) {
            return invalidCredentials();
        }

        // Credentials are good; now it is safe to explain why the account cannot be used.
        AuthResponse statusRefusal = refuseByStatus(user);
        if (statusRefusal != null) {
            return statusRefusal;
        }

        // One session, one token id: the principal and the JWT's jti are the same value, so a
        // token can be tied back to the session that minted it.
        String tokenId = jwtProvider.newTokenId();
        AuthPrincipal principal = AuthPrincipal.of(user, organization, tokenId);
        String jwt = jwtProvider.generateToken(principal);

        // The SecurityContext is deliberately NOT populated here. The old code did that only to
        // hand an Authentication to the token factory; it authenticated nothing, since the
        // response is the token and the next request carries it through the filter chain.

        return AuthResponse.builder()
                .responseCode("100")
                .responseMessage("Login success")
                .jwt(jwt)
                .requiresPasswordChange(passwordEncoder.matches(DEFAULT_PASSWORD, user.getPassword()))
                .build();
    }

    /**
     * Which cooperative a login is for.
     *
     * <p>An explicit slug wins and, if it does not resolve, the login fails -- it must never
     * fall through to prefix derivation, or naming a cooperative would be a way to search a
     * different one.
     *
     * <p>The prefix fallback exists only so existing {@code CBMC0001}-style numbers keep
     * working while frontends move to {@code /o/{slug}/login}. It fails closed: it never looks
     * a member up globally, and it resolves only when exactly one active organization claims
     * that prefix.
     *
     * @return the organization, or {@code null} -- the caller reports invalid credentials, so
     *         "no such cooperative" and "no such member" look the same from outside
     */
    private Organization resolveLoginOrganization(String requestedSlug, String ledgerID) {
        if (StringUtils.hasText(requestedSlug)) {
            return tenantResolver.activeOrganizationBySlug(requestedSlug).orElse(null);
        }

        String prefix = LedgerIDGen.prefixOf(ledgerID);
        if (prefix == null) {
            return null;
        }
        return tenantResolver.activeOrganizationByLedgerPrefix(prefix).orElse(null);
    }

    /**
     * Refuses a sign-in whose credentials were correct but whose account is not usable.
     *
     * <p>Only the three statuses that mean "not usable" are listed. An unrecognised status is
     * allowed through rather than treated as a lockout: status is a workflow field, not the
     * security boundary, and turning an unexpected value into a refusal would lock out real
     * members over a data-entry variant.
     *
     * @return the refusal, or {@code null} when the account may sign in
     */
    private AuthResponse refuseByStatus(User user) {
        String status = user.getStatus() == null ? "" : user.getStatus().trim();

        if ("NEW".equalsIgnoreCase(status) || "PENDING".equalsIgnoreCase(status)) {
            return failedAuth("Your account has been created, please wait for the admin to review "
                    + "and activate your account!\n\nYou will be notified via email once your "
                    + "account is activated❤️.");
        }
        if ("SUSPENDED".equalsIgnoreCase(status)) {
            return failedAuth("This account has been suspended. Please contact an administrator.");
        }
        return null;
    }

    @Override
    public AuthResponse currentUserProfile() {
        User user = requireCurrentUser();

        return AuthResponse.builder()
                .responseCode("100")
                .responseMessage("user found with jwt")
                .user(user)
                .requiresPasswordChange(passwordEncoder.matches(DEFAULT_PASSWORD, user.getPassword()))
                .build();
    }

    @Override
    public User requireCurrentUser() {
        AuthPrincipal principal = CurrentAuth.require();
        return userRepository
                .findByIdAndOrganizationId(principal.userId(), principal.organizationId())
                .orElseThrow(() -> {
                    // The token verified, so the member existed when it was issued and has since
                    // been deleted or moved. Treat it as unauthenticated rather than guessing.
                    log.warn("Token for user {} in organization {} no longer resolves to a member.",
                            principal.userId(), principal.organizationId());
                    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated.");
                });
    }

    // ============================================================== record management

    @Override
    @Transactional
    public User updateOwnProfile(UserProfileUpdateRequest request) {
        User user = requireCurrentUser();

        applyIfPresent(request.getEmail(), email -> {
            if (userRepository.existsByEmailIgnoreCaseAndOrganizationIdAndIdNot(
                    email, user.getOrganization().getId(), user.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Another member of this cooperative already uses that email address.");
            }
            user.setEmail(email);
        });

        applyIfPresent(request.getPhone(), phone -> {
            if (userRepository.existsByPhoneAndOrganizationIdAndIdNot(
                    phone, user.getOrganization().getId(), user.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Another member of this cooperative already uses that phone number.");
            }
            user.setPhone(phone);
        });

        copyPersonalFields(user, request.getFirstName(), request.getMiddleName(),
                request.getLastName(), request.getGender(), request.getMarital());
        copyAddressFields(user, request.getAddress(), request.getHomeTown(), request.getState(),
                request.getLga(), request.getStation());
        copyNextOfKinFields(user, request.getNextOfKin(), request.getNextOfKinRelationship(),
                request.getNextOfKinAddress(), request.getNextOfKinPhone());

        applyIfPresent(request.getOccupation(), user::setOccupation);
        applyIfPresent(request.getPassport(), user::setPassport);

        // Plans are contributions the member undertakes to pay, not balances they hold.
        applyIfPresent(request.getSavingPlan(), user::setSavingPlan);
        applyIfPresent(request.getSpecialSavingPlan(), user::setSpecialSavingPlan);
        applyIfPresent(request.getSharePlan(), user::setSharePlan);

        return userRepository.save(user);
    }

    @Override
    @Transactional
    public User adminUpdateUser(AdminUserUpdateRequest request) {
        AuthPrincipal principal = CurrentAuth.require();
        if (!principal.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only an administrator can edit a member record.");
        }
        if (request.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required.");
        }

        Long organizationId = principal.organizationId();

        // Scoped load. A member of another cooperative is reported as absent, not forbidden --
        // "403" on a foreign id would confirm the id exists somewhere on the platform.
        User user = userRepository.findByIdAndOrganizationId(request.getUserId(), organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Member not found."));

        boolean editingSelf = Objects.equals(user.getId(), principal.userId());
        if (editingSelf) {
            // Not a tenant-isolation matter: it stops an administrator removing their own
            // access -- with no other admin, nobody could restore it.
            if (request.getRole() != null && request.getRole() != user.getRole()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "You cannot change your own role. Ask another administrator.");
            }
            if (request.getStatus() != null && !request.getStatus().equalsIgnoreCase(user.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "You cannot change your own account status. Ask another administrator.");
            }
        }

        applyIfPresent(request.getEmail(), email -> {
            if (userRepository.existsByEmailIgnoreCaseAndOrganizationIdAndIdNot(
                    email, organizationId, user.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Another member of this cooperative already uses that email address.");
            }
            user.setEmail(email);
        });

        applyIfPresent(request.getPhone(), phone -> {
            if (userRepository.existsByPhoneAndOrganizationIdAndIdNot(
                    phone, organizationId, user.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Another member of this cooperative already uses that phone number.");
            }
            user.setPhone(phone);
        });

        copyPersonalFields(user, request.getFirstName(), request.getMiddleName(),
                request.getLastName(), request.getGender(), request.getMarital());
        copyAddressFields(user, request.getAddress(), request.getHomeTown(), request.getState(),
                request.getLga(), request.getStation());
        copyNextOfKinFields(user, request.getNextOfKin(), request.getNextOfKinRelationship(),
                request.getNextOfKinAddress(), request.getNextOfKinPhone());

        applyIfPresent(request.getPsn(), user::setPsn);
        applyIfPresent(request.getOccupation(), user::setOccupation);
        applyIfPresent(request.getVerNo(), user::setVerNo);
        applyIfPresent(request.getPassport(), user::setPassport);
        applyIfPresent(request.getPaymentType(), user::setPaymentType);

        applyIfPresent(request.getSavingPlan(), user::setSavingPlan);
        applyIfPresent(request.getSpecialSavingPlan(), user::setSpecialSavingPlan);
        applyIfPresent(request.getSharePlan(), user::setSharePlan);

        // Privileged, and reachable only from here: the self-service request type has no such
        // fields, so a member cannot set either on themselves.
        if (request.getRole() != null && request.getRole() != user.getRole()) {
            log.info("Administrator {} changed the role of member {} to {} in organization {}.",
                    principal.userId(), user.getId(), request.getRole(), organizationId);
            user.setRole(request.getRole());
        }
        if (StringUtils.hasText(request.getStatus())) {
            user.setStatus(request.getStatus().trim().toUpperCase());
        }

        // Neither ledgerID nor password nor organization is assignable above. That is the whole
        // difference between this method and the updateUser(User) it replaces.
        return userRepository.save(user);
    }

    @Override
    @Transactional
    public User activateUser(Long userId) throws UserException {
        AuthPrincipal principal = CurrentAuth.require();
        if (!principal.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only an administrator can activate a member.");
        }

        User user = userRepository.findByIdAndOrganizationId(userId, principal.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Member not found."));

        Organization organization = user.getOrganization();
        String orgName = organizationService.displayName(organization);
        String names = fullName(user);

        // Only quote the temporary password if the record still has it. Announcing "123456" to
        // a member who set their own password months ago is both wrong and alarming.
        boolean stillDefault = passwordEncoder.matches(DEFAULT_PASSWORD, user.getPassword());
        String credentials = "\nMembership Number: " + user.getLedgerID()
                + (stillDefault ? "\nTemporary Password: " + DEFAULT_PASSWORD : "");

        emailService.sendEmail(EmailDetails.builder()
                .recipient(user.getEmail())
                .senderName(organizationService.emailSenderName(organization))
                .subject("Account Activated")
                .message("Congratulations, your " + orgName + " account has been activated.\n\n"
                        + "Account Name: " + names
                        + credentials
                        + "\n\nYou can now sign in with your Membership Number and password."
                        + organizationService.signInUrlBlock(organization)
                        + "\n\n" + organizationService.emailSignature(organization))
                .build());

        user.setStatus("ACTIVE");
        return userRepository.save(user);
    }

    // ======================================================================= passwords

    @Override
    @Transactional
    public CoopResponse changePassword(User user, PasswordDto passwordDto) {
        if (user == null || user.getOrganization() == null) {
            return failed("Not authenticated.");
        }
        Long organizationId = user.getOrganization().getId();

        if (!StringUtils.hasText(passwordDto.getOtp())) {
            return failed("invalid OTP detected!");
        }

        // The code is looked up for this member's own organization, address and purpose. A code
        // issued for signup, or for the same address at another cooperative, does not match.
        OtpCheck check = otpService.verify(organizationId, user.getEmail(),
                OtpPurpose.PASSWORD_CHANGE, passwordDto.getOtp());
        switch (check) {
            case MISSING -> {
                return failed("you did not send any OTP");
            }
            case MISMATCH -> {
                return failed("OTP is not correct");
            }
            case EXPIRED -> {
                return failed("OTP expired");
            }
            case VALID -> {
                // fall through to the password rules
            }
        }

        if (!passwordEncoder.matches(passwordDto.getOldPassword(), user.getPassword())) {
            return failed("Old password is incorrect");
        }
        if (!Objects.equals(passwordDto.getNewPassword(), passwordDto.getConfirmPassword())) {
            return failed("New password and confirmation password do not match");
        }
        if (passwordEncoder.matches(passwordDto.getNewPassword(), user.getPassword())) {
            return failed("New password cannot be the same as the old password");
        }
        String strength = strengthError(passwordDto.getNewPassword());
        if (strength != null) {
            return failed(strength);
        }

        user.setPassword(passwordEncoder.encode(passwordDto.getNewPassword()));
        userRepository.save(user);

        // Spend the code. Leaving it live would be a second, silent password change waiting.
        otpService.invalidate(organizationId, user.getEmail(), OtpPurpose.PASSWORD_CHANGE);

        emailService.sendEmail(passwordChangedEmail(user));

        return succeeded("Password changed successfully");
    }

    @Override
    @Transactional
    public CoopResponse changePasswordSimple(User user, String oldPassword, String newPassword) {
        // No OTP: this is the mandatory first-login change, and the JWT plus the current
        // password already prove who is asking.
        if (user == null) {
            return failed("Not authenticated.");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            return failed("Current password is incorrect");
        }
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            return failed("New password cannot be the same as the old password");
        }
        String strength = strengthError(newPassword);
        if (strength != null) {
            return failed(strength);
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        emailService.sendEmail(passwordChangedEmail(user));

        return succeeded("Password changed successfully");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Tenant-scoped by construction: the organization is resolved from the slug the member
     * came through, the member is then found only within it, and the code is issued against
     * that organization. There is no email-only lookup anywhere in the flow, so a code can
     * never be minted for an identically-addressed member of a different cooperative.
     *
     * <p>The response is the same whether or not a member matched. An unauthenticated endpoint
     * that answers differently is a membership oracle for any address someone cares to try.
     */
    @Override
    @Transactional
    public CoopResponse requestPasswordReset(ForgotPasswordRequest request) {
        Organization organization = organizationService.resolveRequestedOrganization(
                request.getOrganization());

        findForReset(organization.getId(), request.getLedgerID(), request.getEmail())
                // The code goes to the address on the member's record. Taking the recipient from
                // the request would let anyone have a reset code delivered to themselves.
                .filter(member -> StringUtils.hasText(member.getEmail()))
                .ifPresent(member -> otpService.issue(
                        organization, member.getEmail(), OtpPurpose.PASSWORD_RESET));

        return succeeded("If that account exists, a reset code has been sent to the email "
                + "address on file.");
    }

    @Override
    @Transactional
    public CoopResponse resetPassword(ResetPasswordRequest request) {
        Organization organization = organizationService.resolveRequestedOrganization(
                request.getOrganization());

        if (!Objects.equals(request.getNewPassword(), request.getConfirmPassword())) {
            return failed("New password and confirmation password do not match");
        }
        String strength = strengthError(request.getNewPassword());
        if (strength != null) {
            return failed(strength);
        }

        User member = findForReset(organization.getId(), request.getLedgerID(), request.getEmail())
                .orElse(null);

        // Every failure past this point answers identically, so the endpoint cannot be used to
        // discover which membership numbers or addresses belong to this cooperative.
        if (member == null || !StringUtils.hasText(member.getEmail())) {
            return failed(INVALID_RESET);
        }

        OtpCheck check = otpService.verify(organization.getId(), member.getEmail(),
                OtpPurpose.PASSWORD_RESET, request.getOtp());
        if (check != OtpCheck.VALID) {
            return failed(INVALID_RESET);
        }

        member.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(member);

        otpService.invalidate(organization.getId(), member.getEmail(), OtpPurpose.PASSWORD_RESET);

        emailService.sendEmail(passwordChangedEmail(member));

        log.info("Password reset completed for member {} in organization {}.",
                member.getId(), organization.getId());

        return succeeded("Your password has been reset. You can now sign in with your new password.");
    }

    /**
     * Finds the member a reset request refers to, by membership number or by email, always
     * within one organization.
     *
     * <p>Membership number is tried first because it is the account identifier; email is
     * accepted for a member who has forgotten their number.
     */
    private Optional<User> findForReset(Long organizationId, String ledgerID, String email) {
        String number = trimToNull(ledgerID);
        if (number != null) {
            return userRepository.findByLedgerIDAndOrganizationId(number, organizationId);
        }
        String address = trimToNull(email);
        if (address != null) {
            return userRepository.findByEmailIgnoreCaseAndOrganizationId(address, organizationId);
        }
        return Optional.empty();
    }

    // ========================================================================= helpers

    private String strengthError(String password) {
        if (!StringUtils.hasText(password) || !STRONG_PASSWORD.matcher(password).matches()) {
            return STRENGTH_MESSAGE;
        }
        return null;
    }

    private String timingDecoyHash() {
        String hash = timingDecoyHash;
        if (hash == null) {
            // A race here just computes the value twice, which is harmless.
            hash = passwordEncoder.encode(UUID.randomUUID().toString());
            timingDecoyHash = hash;
        }
        return hash;
    }

    private static void copyPersonalFields(User user, String firstName, String middleName,
            String lastName, String gender, String marital) {
        applyIfPresent(firstName, user::setFirstName);
        applyIfPresent(middleName, user::setMiddleName);
        applyIfPresent(lastName, user::setLastName);
        applyIfPresent(gender, user::setGender);
        applyIfPresent(marital, user::setMarital);
    }

    private static void copyAddressFields(User user, String address, String homeTown, String state,
            String lga, String station) {
        applyIfPresent(address, user::setAddress);
        applyIfPresent(homeTown, user::setHomeTown);
        applyIfPresent(state, user::setState);
        applyIfPresent(lga, user::setLga);
        applyIfPresent(station, user::setStation);
    }

    private static void copyNextOfKinFields(User user, String nextOfKin, String relationship,
            String address, String phone) {
        applyIfPresent(nextOfKin, user::setNextOfKin);
        applyIfPresent(relationship, user::setNextOfKinRelationship);
        applyIfPresent(address, user::setNextOfKinAddress);
        applyIfPresent(phone, user::setNextOfKinPhone);
    }

    /** Applies a value only when the caller actually sent one, so omitted fields keep their value. */
    private static <T> void applyIfPresent(T value, java.util.function.Consumer<T> setter) {
        if (value instanceof String text) {
            if (StringUtils.hasText(text)) {
                @SuppressWarnings("unchecked")
                T trimmed = (T) text.trim();
                setter.accept(trimmed);
            }
            return;
        }
        if (value != null) {
            setter.accept(value);
        }
    }

    private EmailDetails welcomeEmail(User savedUser, Organization organization, String status) {
        String orgName = organizationService.displayName(organization);
        boolean awaitingApproval = "NEW".equalsIgnoreCase(status) || "PENDING".equalsIgnoreCase(status);

        String closing = awaitingApproval
                ? "Your account is currently awaiting approval by the Administrator.\n"
                        + "You will receive another email once your account has been activated.\n\n"
                : "You can sign in now with your Membership Number and the temporary password "
                        + "above.\n\n";

        return EmailDetails.builder()
                .recipient(savedUser.getEmail())
                .senderName(organizationService.emailSenderName(organization))
                .subject("Welcome to " + orgName)
                .message("Dear " + fullName(savedUser) + ",\n\n"
                        + "Your registration has been received successfully.\n\n"
                        + "Membership Number: " + savedUser.getLedgerID() + "\n"
                        + "Temporary Password: " + DEFAULT_PASSWORD
                        + organizationService.signInUrlBlock(organization) + "\n\n"
                        + closing
                        + "Thank you for joining " + orgName + ".\n\n"
                        + organizationService.emailSignature(organization))
                .build();
    }

    private EmailDetails passwordChangedEmail(User user) {
        Organization organization = user.getOrganization();
        return EmailDetails.builder()
                .recipient(user.getEmail())
                .senderName(organizationService.emailSenderName(organization))
                .subject("Password Change Notification")
                .message("This is to notify you that the password on your "
                        + organizationService.displayName(organization)
                        + " account has just been changed.\n"
                        + "If it was not you, kindly contact an ADMIN immediately!\n\n"
                        + organizationService.emailSignature(organization))
                .build();
    }

    private static String fullName(User user) {
        return String.format("%s %s %s",
                user.getFirstName(),
                user.getMiddleName() == null ? "" : user.getMiddleName(),
                user.getLastName()).replaceAll("\\s+", " ").trim();
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /**
     * The one response every credential failure produces.
     *
     * <p>Unknown cooperative, unknown membership number and wrong password are all this. Any
     * difference between them tells an attacker which membership numbers are real, which is
     * exactly the list they would want before guessing passwords.
     */
    private static AuthResponse invalidCredentials() {
        return failedAuth(INVALID_CREDENTIALS);
    }

    private static AuthResponse failedAuth(String message) {
        return AuthResponse.builder()
                .responseCode("419")
                .responseMessage(message)
                .jwt(null)
                .build();
    }

    private static CoopResponse succeeded(String message) {
        return CoopResponse.builder().responseCode("100").responseMessage(message).build();
    }

    private static CoopResponse failed(String message) {
        return CoopResponse.builder().responseCode("419").responseMessage(message).build();
    }
}
