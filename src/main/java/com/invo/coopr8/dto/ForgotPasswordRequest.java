package com.invo.coopr8.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Step one of a forgotten-password reset: ask for a code.
 *
 * <p>The organization is required. A password reset keyed on email alone would be a
 * cross-tenant hole by construction: the same address may be a member of several
 * cooperatives, and a global lookup would pick one of them arbitrarily and email a code
 * capable of taking over that account.
 *
 * <p>Either identifier may be given. Whichever is used, the code is sent to the address
 * already on the member's record -- never to an address supplied in this request.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ForgotPasswordRequest {

    /** Slug of the cooperative, from the {@code /o/{slug}/forgot-password} URL. */
    private String organization;

    /** Membership number, e.g. {@code CBMC0001}. Preferred. */
    private String ledgerID;

    /** Email on file, as an alternative for a member who has forgotten their number. */
    private String email;
}
