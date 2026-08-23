package com.invo.coopr8.service;

/**
 * Outcome of checking a submitted one-time code.
 *
 * <p>An enum rather than a boolean so each caller can decide how much to disclose. An
 * authenticated member changing their own password can safely be told "that code has
 * expired"; the unauthenticated reset endpoint collapses every failure into one message,
 * because distinguishing "no code was ever issued for this address" from "wrong code" would
 * tell an anonymous caller whether an address is a member of this cooperative.
 */
public enum OtpCheck {

    /** Correct, unexpired, and issued for this tenant and this purpose. */
    VALID,

    /** No live code exists for this (tenant, email, purpose). */
    MISSING,

    /** A code exists but the submitted digits do not match it. */
    MISMATCH,

    /** The code matched but is past its expiry. */
    EXPIRED
}
