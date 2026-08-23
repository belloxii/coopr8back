package com.invo.coopr8.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to issue or verify a one-time code.
 *
 * <p>Codes are issued per tenant, so the tenant has to be identifiable. For the
 * unauthenticated flows that means the {@link #organization} slug from the URL the user is
 * on; for {@code changePass} the tenant comes from the caller's JWT and this field is
 * ignored.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class OTPRequest {

    /** Slug of the cooperative the code belongs to. Ignored for authenticated actions. */
    private String organization;

    private String email;
    private String otp;
    private String action;
}
