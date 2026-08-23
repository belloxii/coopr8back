package com.invo.coopr8.utils;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Builds a member's ledger ID from the owning organization's prefix, and reads the prefix
 * back out of one.
 *
 * <p>The prefix is tenant configuration ({@code Organization.ledgerPrefix}), not a
 * constant: cooperative "ABC" issues ABC0001, cooperative "XYZ" issues XYZ0001. There
 * is deliberately no default prefix -- neither a legacy society code nor the COOPR8
 * platform name -- because a fallback would quietly mint IDs under the wrong identity.
 */
public final class LedgerIDGen {

    private static final int SEQUENCE_DIGITS = 4;

    /**
     * A ledger prefix is letters only.
     *
     * <p>That restriction is what makes {@link #prefixOf(String)} unambiguous: with digits
     * allowed in a prefix there would be no way to tell where the prefix ends and the member
     * number begins, and tenant discovery from a membership number would become a guess.
     */
    private static final Pattern LEDGER_PREFIX = Pattern.compile("^[A-Za-z]+$");

    /** {@code CBMC0001} -> prefix {@code CBMC}, sequence {@code 0001}. */
    private static final Pattern LEDGER_ID = Pattern.compile("^([A-Za-z]+)(\\d+)$");

    private LedgerIDGen() {
    }

    /**
     * @param ledgerPrefix the organization's configured ledger prefix, e.g. {@code "ABC"}
     * @param sequence     the next member number within that organization, 1-based
     * @return e.g. {@code "ABC0001"}
     * @throws IllegalArgumentException if the organization has no usable ledger prefix
     */
    public static String generate(String ledgerPrefix, int sequence) {
        if (!isValidPrefix(ledgerPrefix)) {
            throw new IllegalArgumentException(
                    "Organization has no usable ledgerPrefix (letters only); cannot generate a ledger ID.");
        }
        return normalizePrefix(ledgerPrefix)
                + String.format("%0" + SEQUENCE_DIGITS + "d", sequence);
    }

    /** Whether a value is acceptable as an organization's ledger prefix. */
    public static boolean isValidPrefix(String ledgerPrefix) {
        return ledgerPrefix != null && LEDGER_PREFIX.matcher(ledgerPrefix.trim()).matches();
    }

    /**
     * Canonical form of a ledger prefix: trimmed and upper-cased.
     *
     * <p>Storing and comparing one form only is what keeps the global uniqueness constraint
     * meaningful -- {@code abc} and {@code ABC} must not be two different tenants' prefixes.
     */
    public static String normalizePrefix(String ledgerPrefix) {
        return ledgerPrefix == null ? null : ledgerPrefix.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * The organization prefix embedded in a membership number, upper-cased.
     *
     * <p>Used only as the login fallback for members who type a bare membership number with no
     * cooperative in the URL. It returns {@code null} for anything that is not
     * {@code LETTERS + DIGITS}, so a malformed or crafted value produces no tenant rather than
     * a partial match -- and the caller still has to find exactly one organization claiming
     * the prefix before it means anything.
     *
     * @return the prefix, or {@code null} when the value is not a well-formed ledger ID
     */
    public static String prefixOf(String ledgerID) {
        if (ledgerID == null) {
            return null;
        }
        var matcher = LEDGER_ID.matcher(ledgerID.trim());
        return matcher.matches() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }
}
