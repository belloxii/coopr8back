package com.invo.coopr8.model;

/**
 * What a one-time code was issued for.
 *
 * <p>A code is only accepted by the flow that asked for it. Without this, a code obtained
 * from the (unauthenticated) signup endpoint could be replayed against the password-reset
 * endpoint: same email, same five digits, different consequence.
 */
public enum OtpPurpose {

    /** Proving control of an email address during self-service signup. */
    SIGNUP,

    /** Resetting a forgotten password. Issued only for an existing member of one tenant. */
    PASSWORD_RESET,

    /** Confirming a password change by an already-authenticated member. */
    PASSWORD_CHANGE;

    /**
     * Maps the {@code action} strings the frontend has always sent onto purposes.
     *
     * @return the purpose, or {@code null} when the action is not one we issue codes for
     */
    public static OtpPurpose fromAction(String action) {
        if (action == null) {
            return null;
        }
        return switch (action.trim().toLowerCase()) {
            case "signup" -> SIGNUP;
            case "forgetpass", "forgotpass", "password_reset" -> PASSWORD_RESET;
            case "changepass", "password_change" -> PASSWORD_CHANGE;
            default -> null;
        };
    }
}
