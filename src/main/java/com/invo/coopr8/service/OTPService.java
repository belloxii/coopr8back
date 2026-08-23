package com.invo.coopr8.service;

import com.invo.coopr8.dto.CoopResponse;
import com.invo.coopr8.dto.OTPRequest;
import com.invo.coopr8.model.Organization;
import com.invo.coopr8.model.OtpPurpose;

/**
 * Issues and checks one-time codes, always inside one tenant and for one purpose.
 *
 * <p>Every method takes the organization explicitly. There is no overload that infers the
 * tenant, because the whole class of bug this replaces was a lookup keyed on email alone: a
 * member's address may exist at several cooperatives, and picking one arbitrarily meant a
 * code issued by cooperative A could reset an account at cooperative B.
 */
public interface OTPService {

    /** Endpoint-facing: issue a code for the {@code action} named in the request. */
    CoopResponse sendOTP(OTPRequest otpRequest);

    /** Endpoint-facing: report whether a submitted code is currently valid. */
    CoopResponse validateOTP(OTPRequest otpRequest);

    /**
     * Generates a code, replaces any outstanding one for the same tenant/email/purpose, and
     * emails it under the organization's own branding.
     *
     * @return {@code false} when the message could not even be queued for delivery
     */
    boolean issue(Organization organization, String email, OtpPurpose purpose);

    /** Checks a submitted code without consuming it. */
    OtpCheck verify(Long organizationId, String email, OtpPurpose purpose, String submittedCode);

    /**
     * Deletes the code for this tenant/email/purpose.
     *
     * <p>Called once a code has done its job, so it cannot be replayed -- a reset code that
     * survives the reset is a second, silent password change waiting to happen.
     */
    void invalidate(Long organizationId, String email, OtpPurpose purpose);
}
