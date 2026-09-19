package com.invo.coopr8.utils;

import java.security.SecureRandom;

/**
 * Generates the one-time codes used for signup verification, password change and password reset.
 *
 * <p>{@link SecureRandom}, not {@code java.util.Random}: since Phase 2 an OTP is a credential that
 * can reset a password, and {@code java.util.Random} is a linear congruential generator whose
 * entire future output is recoverable from a couple of observed values. A member who legitimately
 * requests one code could otherwise predict the next member's.
 *
 * <p>The code is five digits because that is the length the existing accounts, emails and frontend
 * input were built around. Five digits is a small keyspace, so the surrounding flow -- not the code
 * length -- is what keeps it safe: only one code is ever live per (organization, email, purpose),
 * it expires, and it is deleted the moment it is used.
 */
public final class OTPGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int LENGTH = 6;

    private OTPGenerator() {
    }

    public static String generateOTP() {
        StringBuilder otp = new StringBuilder(LENGTH);
        for (int count = 0; count < LENGTH; count++) {
            otp.append(RANDOM.nextInt(10));
        }
        return otp.toString();
    }
}
