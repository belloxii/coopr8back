package com.invo.coopr8.service;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.invo.coopr8.dto.CoopResponse;
import com.invo.coopr8.dto.EmailDetails;
import com.invo.coopr8.dto.OTPRequest;
import com.invo.coopr8.dto.OTPResponse;
import com.invo.coopr8.model.OTP;
import com.invo.coopr8.model.Organization;
import com.invo.coopr8.model.OtpPurpose;
import com.invo.coopr8.model.User;
import com.invo.coopr8.repository.OTPRepository;
import com.invo.coopr8.repository.UserRepository;
import com.invo.coopr8.security.AuthPrincipal;
import com.invo.coopr8.security.CurrentAuth;
import com.invo.coopr8.utils.OTPGenerator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Tenant-aware one-time codes.
 *
 * <p>Three things changed here in Phase 2, and each closes a distinct hole:
 * <ol>
 *   <li><b>Codes belong to an organization.</b> Lookups are
 *       {@code (organizationId, email, purpose)}. Previously a code was found by email alone,
 *       so once two cooperatives shared a member's address the two rows were
 *       indistinguishable.</li>
 *   <li><b>Codes belong to a purpose.</b> A code obtained from the unauthenticated signup
 *       endpoint cannot be presented to the password-reset endpoint.</li>
 *   <li><b>The recipient address is never taken from the request for an existing member.</b>
 *       It is read from the member's own record, so a code cannot be redirected by asking for
 *       it to be sent somewhere else.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OTPServiceImpl implements OTPService {

    private static final int VALIDITY_MINUTES = 10;

    private final OTPRepository otpRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final OrganizationService organizationService;

    // -------------------------------------------------------------- endpoint-facing

    @Override
    @Transactional
    public CoopResponse sendOTP(OTPRequest otpRequest) {
        OtpPurpose purpose = OtpPurpose.fromAction(otpRequest.getAction());
        if (purpose == null) {
            return failure("Invalid action detected!");
        }

        // A member changing their own password is already authenticated, so their tenant and
        // their address both come from their record -- the request body is not consulted for
        // either. Otherwise the tenant comes from the slug the caller is visiting.
        if (purpose == OtpPurpose.PASSWORD_CHANGE) {
            return sendToAuthenticatedMember();
        }

        Organization organization = organizationService.resolveRequestedOrganization(
                otpRequest.getOrganization());
        String email = trimToNull(otpRequest.getEmail());
        if (email == null) {
            return failure("No email address provided. Please contact an admin to add your email.");
        }

        Optional<User> member = userRepository.findByEmailIgnoreCaseAndOrganizationId(
                email, organization.getId());

        if (purpose == OtpPurpose.SIGNUP) {
            return sendForSignup(organization, email, member);
        }
        return sendForPasswordReset(organization, member);
    }

    /** Signup: the address must NOT already be a member of this cooperative. */
    private CoopResponse sendForSignup(Organization organization, String email, Optional<User> member) {
        if (member.isPresent()) {
            String status = member.get().getStatus();
            if ("NEW".equalsIgnoreCase(status) || "PENDING".equalsIgnoreCase(status)) {
                return failure("Your account is pending admin approval, please wait or contact an admin.");
            }
            return failure("There is already a registered user with this email, try to log in instead.");
        }
        return issue(organization, email, OtpPurpose.SIGNUP)
                ? success("otp generated and sent successfully")
                : failure("Could not send the OTP email. Please check the email address and try again, "
                        + "or contact an admin.");
    }

    /**
     * Password reset: the address MUST already be a member of this cooperative -- but the
     * response never says so.
     *
     * <p>The same message comes back whether or not the address is a member, because this
     * endpoint is unauthenticated and a differing response would turn it into a membership
     * oracle: "is ada@example.com a member of this cooperative?" answered for free, for any
     * address, at any tenant.
     */
    private CoopResponse sendForPasswordReset(Organization organization, Optional<User> member) {
        member.ifPresent(user -> issue(organization, user.getEmail(), OtpPurpose.PASSWORD_RESET));
        return success("If that email belongs to a member, a one-time code is on its way.");
    }

    /** Password change: tenant and recipient both come from the authenticated member. */
    private CoopResponse sendToAuthenticatedMember() {
        AuthPrincipal principal = CurrentAuth.require();
        User user = userRepository
                .findByIdAndOrganizationId(principal.userId(), principal.organizationId())
                .orElse(null);
        if (user == null || !StringUtils.hasText(user.getEmail())) {
            return failure("Your account has no email address on file. Please contact an admin.");
        }

        Organization organization = organizationService.currentOrganizationEntity();
        return issue(organization, user.getEmail(), OtpPurpose.PASSWORD_CHANGE)
                ? success("otp generated and sent successfully")
                : failure("Could not send the OTP email. Please try again, or contact an admin.");
    }

    @Override
    public CoopResponse validateOTP(OTPRequest otpRequest) {
        OtpPurpose purpose = OtpPurpose.fromAction(otpRequest.getAction());
        if (purpose == null) {
            return failure("Invalid action detected!");
        }

        Long organizationId;
        String email;
        if (purpose == OtpPurpose.PASSWORD_CHANGE) {
            AuthPrincipal principal = CurrentAuth.require();
            User user = userRepository
                    .findByIdAndOrganizationId(principal.userId(), principal.organizationId())
                    .orElse(null);
            if (user == null) {
                return failure("OTP is not correct");
            }
            organizationId = principal.organizationId();
            email = user.getEmail();
        } else {
            organizationId = organizationService
                    .resolveRequestedOrganization(otpRequest.getOrganization()).getId();
            email = trimToNull(otpRequest.getEmail());
            if (email == null) {
                return failure("OTP is not correct");
            }
        }

        return switch (verify(organizationId, email, purpose, otpRequest.getOtp())) {
            case VALID -> CoopResponse.builder()
                    .responseCode("100")
                    .responseMessage("OTP validated successfully")
                    .otpResponse(OTPResponse.builder().isOTPValid(true).build())
                    .build();
            // "you did not send any OTP" was the previous wording for a missing code and the
            // frontend keys off the message, so it is preserved -- but with responseCode 419
            // rather than the old 100, which reported a failure as a success.
            case MISSING -> failure("you did not send any OTP");
            case EXPIRED -> failure("OTP expired");
            case MISMATCH -> failure("OTP is not correct");
        };
    }

    // ------------------------------------------------------------------- core actions

    @Override
    @Transactional
    public boolean issue(Organization organization, String email, OtpPurpose purpose) {
        String code = OTPGenerator.generateOTP();

        // Supersede any outstanding code for this exact (tenant, email, purpose) so only one
        // is ever live: an old code left behind is a second valid credential.
        otpRepository.deleteForRecipient(organization.getId(), email, purpose);

        // Persisted before the send, not after. Delivery is asynchronous (see EmailServiceImpl),
        // so "send, then save only if it worked" could never actually observe a delivery
        // failure -- and getting it backwards means a member holding a code the database has
        // never heard of. If the send is rejected outright the code simply expires unused.
        otpRepository.saveAndFlush(OTP.builder()
                .email(email)
                .otp(code)
                .organization(organization)
                .purpose(purpose)
                .expiredAt(LocalDateTime.now().plusMinutes(VALIDITY_MINUTES))
                .build());

        String brand = organizationService.displayNameOrPlatform(organization);
        try {
            emailService.sendEmail(EmailDetails.builder()
                    .subject("Do not disclose!!!")
                    .recipient(email)
                    .senderName(brand)
                    .message("Below is your One Time Password. \nThe OTP expires in "
                            + VALIDITY_MINUTES + " minutes. \nInput <" + code + "> to "
                            + describe(purpose) + " in the " + brand + " app.\n\n"
                            + organizationService.signatureOrPlatform(organization))
                    .build());
        } catch (Exception e) {
            // The code itself is never logged.
            log.error("Could not queue the {} OTP email for organization {}: {}",
                    purpose, organization.getId(), e.getMessage());
            return false;
        }
        return true;
    }

    @Override
    public OtpCheck verify(Long organizationId, String email, OtpPurpose purpose, String submittedCode) {
        if (organizationId == null || !StringUtils.hasText(email) || purpose == null
                || !StringUtils.hasText(submittedCode)) {
            return OtpCheck.MISSING;
        }

        OTP stored = otpRepository
                .findByOrganizationIdAndEmailIgnoreCaseAndPurpose(organizationId, email.trim(), purpose)
                .orElse(null);
        if (stored == null) {
            return OtpCheck.MISSING;
        }
        if (!constantTimeEquals(stored.getOtp(), submittedCode.trim())) {
            return OtpCheck.MISMATCH;
        }
        if (stored.getExpiredAt() == null || stored.getExpiredAt().isBefore(LocalDateTime.now())) {
            return OtpCheck.EXPIRED;
        }
        return OtpCheck.VALID;
    }

    @Override
    @Transactional
    public void invalidate(Long organizationId, String email, OtpPurpose purpose) {
        if (organizationId == null || !StringUtils.hasText(email) || purpose == null) {
            return;
        }
        otpRepository.deleteForRecipient(organizationId, email.trim(), purpose);
    }

    // ------------------------------------------------------------------------ helpers

    /**
     * Compares codes without an early exit on the first differing character.
     *
     * <p>A five-digit code has a small enough keyspace that leaking "how many leading digits
     * were right" through response timing is worth avoiding, even though the practical risk
     * over HTTP is slight.
     */
    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null || expected.length() != actual.length()) {
            return false;
        }
        int difference = 0;
        for (int i = 0; i < expected.length(); i++) {
            difference |= expected.charAt(i) ^ actual.charAt(i);
        }
        return difference == 0;
    }

    private static String describe(OtpPurpose purpose) {
        return switch (purpose) {
            case SIGNUP -> "continue your registration";
            case PASSWORD_RESET -> "reset your password";
            case PASSWORD_CHANGE -> "confirm your password change";
        };
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static CoopResponse success(String message) {
        return CoopResponse.builder().responseCode("100").responseMessage(message).build();
    }

    private static CoopResponse failure(String message) {
        return CoopResponse.builder().responseCode("419").responseMessage(message).build();
    }
}
