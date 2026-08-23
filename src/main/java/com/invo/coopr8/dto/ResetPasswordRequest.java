package com.invo.coopr8.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Step two of a forgotten-password reset: redeem the code and set a new password.
 *
 * <p>The organization is carried through from step one and re-resolved here, so the code is
 * looked up inside the same tenant that issued it. A code minted for a member of one
 * cooperative cannot reset an identically-addressed account at another.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ResetPasswordRequest {

    /** Slug of the cooperative, same value used to request the code. */
    private String organization;

    /** Membership number, if that is how the code was requested. */
    private String ledgerID;

    /** Email on file, if that is how the code was requested. */
    private String email;

    /** The six-digit code from the email. */
    private String otp;

    private String newPassword;

    private String confirmPassword;
}
